package com.exchange.domain.order.entity;

import java.time.Instant;

import com.exchange.engine.Side;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문. 저장은 멱등키를 확인하는 INSERT로만 한다.
 *
 * <p>상태 변경 메서드는 행을 잠그고 읽은 체결 반영에서만 쓴다.
 */
@Entity
// 앱 DB 계정은 바꿀 수 있는 컬럼만 UPDATE할 수 있으므로 바뀐 컬럼만 갱신한다.
@DynamicUpdate
@Getter
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

	@Id
	private Long orderId;

	private long accountId;

	private String symbol;

	@Enumerated(EnumType.STRING)
	private Side side;

	private long price;

	private long quantity;

	private long filledQuantity;

	private long reservedAmount;

	@Enumerated(EnumType.STRING)
	private OrderStatus status;

	private Long engineSeq;

	// 취소 요청은 잠그지 않고 이 컬럼만 바꾸므로, 체결 반영이 옛 값으로 덮어쓰지 않게 한다.
	@Column(updatable = false)
	private Instant cancelRequestedAt;

	private String idempotencyKey;

	private String requestHash;

	private Instant createdAt;

	// DB 트리거가 DB 시계로 바꾼다.
	@Column(insertable = false, updatable = false)
	private Instant updatedAt;

	/** 매수 주문에 필요한 예약금. */
	public static long reserveAmount(long price, long remaining) {
		return Math.multiplyExact(price, remaining);
	}

	/** 남은 수량. */
	public long remaining() {
		return quantity - filledQuantity;
	}

	/** 엔진에 접수됐다. */
	public void accept(long seq) {
		requireStatus(status == OrderStatus.ACCEPTED);
		engineSeq = seq;
		status = OrderStatus.OPEN;
	}

	/** 체결을 반영하고, 매수면 풀어야 할 예약금을 반환한다. */
	public long fill(long traded) {
		requireStatus(OrderStatus.ON_BOOK.contains(status) && traded <= remaining());
		filledQuantity += traded;
		status = remaining() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
		return shrinkReserve(side == Side.BUY ? reserveAmount(price, remaining()) : 0);
	}

	/** 남은 수량을 취소하고, 매수면 풀어야 할 예약금을 반환한다. */
	public long cancel() {
		requireStatus(OrderStatus.ON_BOOK.contains(status));
		status = OrderStatus.CANCELED;
		return shrinkReserve(0);
	}

	// 예약금을 줄이고 차액을 반환한다.
	private long shrinkReserve(long next) {
		long released = reservedAmount - next;
		reservedAmount = next;
		return released;
	}

	// 엔진과 DB가 어긋났으면 반영하지 않고 종목 처리를 멈추게 한다.
	private void requireStatus(boolean allowed) {
		if (!allowed) {
			throw new IllegalStateException("반영할 수 없는 주문 상태: orderId=" + orderId + ", status=" + status);
		}
	}
}
