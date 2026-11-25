package com.exchange.domain.account.entity;

import java.math.BigInteger;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보유 종목. 평균단가의 반올림 오차가 쌓이지 않도록 매입 원가 합계를 저장한다.
 *
 * <p>증감 메서드는 행을 잠그고 읽은 체결 반영에서만 쓴다.
 */
@Entity
// 앱 DB 계정은 바꿀 수 있는 컬럼만 UPDATE할 수 있으므로 바뀐 컬럼만 갱신한다.
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Position {

	@EmbeddedId
	private PositionId id;

	private long quantity;

	private long reservedQuantity;

	private long totalCost;

	private long realizedPnl;

	/** 매수 체결을 반영한다. */
	public void buy(long bought, long amount) {
		quantity = Math.addExact(quantity, bought);
		totalCost = Math.addExact(totalCost, amount);
	}

	/** 매도 체결을 반영한다. 줄어드는 원가는 원 미만을 버리고, 전량 매도면 남은 원가를 모두 뺀다. */
	public void sell(long sold, long amount) {
		long cost = costOf(sold);
		quantity = Math.subtractExact(quantity, sold);
		reservedQuantity = Math.subtractExact(reservedQuantity, sold);
		totalCost = Math.subtractExact(totalCost, cost);
		realizedPnl = Math.addExact(realizedPnl, Math.subtractExact(amount, cost));
	}

	/** 취소: 예약 수량을 푼다. */
	public void release(long released) {
		reservedQuantity = Math.subtractExact(reservedQuantity, released);
	}

	// 원가 × 수량이 long을 넘을 수 있어 BigInteger로 계산한다.
	private long costOf(long sold) {
		return BigInteger.valueOf(totalCost).multiply(BigInteger.valueOf(sold))
			.divide(BigInteger.valueOf(quantity)).longValueExact();
	}
}
