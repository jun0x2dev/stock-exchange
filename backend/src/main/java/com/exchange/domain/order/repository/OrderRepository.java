package com.exchange.domain.order.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.exchange.domain.order.entity.Order;
import com.exchange.domain.order.entity.OrderStatus;
import com.exchange.engine.Side;

import jakarta.persistence.LockModeType;

/** 주문 저장소. */
public interface OrderRepository extends Repository<Order, Long> {

	/** 엔진에 넣을 때 필요한 주문 정보. */
	record EngineOrder(long orderId, long accountId, Side side, long price, long remaining, Instant createdAt,
			Instant cancelRequestedAt) {
	}

	/** 취소할 주문과 요청 시각. */
	record CancelRequest(long orderId, Instant requestedAt) {
	}

	/** 멱등키가 처음일 때만 저장하고 ID를 반환한다. 이미 있으면 비어 있다. */
	@Query(nativeQuery = true, value = """
		INSERT INTO orders (account_id, symbol, side, price, quantity, reserved_amount, idempotency_key, request_hash)
		VALUES (:accountId, :symbol, :side, :price, :quantity, :reservedAmount, :key, :hash)
		ON CONFLICT (account_id, idempotency_key) DO NOTHING
		RETURNING order_id
		""")
	Optional<Long> insertIfAbsent(long accountId, String symbol, String side, long price, long quantity,
		long reservedAmount, String key, String hash);

	/** 멱등키로 찾는다. */
	Optional<Order> findByAccountIdAndIdempotencyKey(long accountId, String idempotencyKey);

	/** 주문을 찾는다. */
	Optional<Order> findById(long orderId);

	/** 사용자의 주문을 찾는다. */
	@Query("""
		SELECT o FROM Order o JOIN Account a ON a.accountId = o.accountId
		 WHERE o.orderId = :orderId AND a.userId = :userId
		""")
	Optional<Order> findOwned(long orderId, long userId);

	/** 커서보다 오래된 주문을 최신순으로 조회한다. */
	List<Order> findByAccountIdAndStatusInAndOrderIdLessThanOrderByOrderIdDesc(long accountId,
		Collection<OrderStatus> statuses, long cursor, Limit limit);

	/** 아직 체결될 수 있는 주문에 취소 요청을 한 번만 남긴다. */
	@Modifying(clearAutomatically = true)
	@Query(nativeQuery = true, value = """
		UPDATE orders SET cancel_requested_at = clock_timestamp()
		 WHERE order_id = :orderId AND cancel_requested_at IS NULL
		   AND status IN ('ACCEPTED', 'OPEN', 'PARTIALLY_FILLED')
		""")
	int requestCancel(long orderId);

	/** 아직 엔진에 들어가지 않은 주문. 커밋됐지만 전달되지 못한 주문도 여기에 남는다. */
	@Query("""
		SELECT new com.exchange.domain.order.repository.OrderRepository$EngineOrder(o.orderId, o.accountId, o.side,
		       o.price, o.quantity - o.filledQuantity, o.createdAt, o.cancelRequestedAt)
		  FROM Order o WHERE o.symbol = :symbol AND o.status = com.exchange.domain.order.entity.OrderStatus.ACCEPTED
		 ORDER BY o.orderId
		""")
	List<EngineOrder> findAccepted(String symbol, Limit limit);

	/** 호가창에 있고 취소 요청을 받은 주문. */
	@Query("""
		SELECT new com.exchange.domain.order.repository.OrderRepository$CancelRequest(o.orderId, o.cancelRequestedAt)
		  FROM Order o WHERE o.symbol = :symbol AND o.cancelRequestedAt IS NOT NULL
		   AND o.status IN (com.exchange.domain.order.entity.OrderStatus.OPEN,
		                    com.exchange.domain.order.entity.OrderStatus.PARTIALLY_FILLED)
		 ORDER BY o.cancelRequestedAt, o.orderId
		""")
	List<CancelRequest> findCancelRequested(String symbol, Limit limit);

	/** 호가창을 복원할 주문. 접수 순번 순이어야 시간 우선이 유지된다. */
	@Query("""
		SELECT new com.exchange.domain.order.repository.OrderRepository$EngineOrder(o.orderId, o.accountId, o.side,
		       o.price, o.quantity - o.filledQuantity, o.createdAt, o.cancelRequestedAt)
		  FROM Order o WHERE o.symbol = :symbol
		   AND o.status IN (com.exchange.domain.order.entity.OrderStatus.OPEN,
		                    com.exchange.domain.order.entity.OrderStatus.PARTIALLY_FILLED)
		 ORDER BY o.engineSeq
		""")
	List<EngineOrder> findOnBook(String symbol);

	/** 주문을 낸 계좌들. 잠금 순서를 지키려고 주문을 잠그기 전에 계좌부터 알아낸다. */
	@Query("SELECT DISTINCT o.accountId FROM Order o WHERE o.orderId IN :orderIds")
	List<Long> findAccountIds(Collection<Long> orderIds);

	/** 주문 ID 순으로 잠근다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.orderId IN :orderIds ORDER BY o.orderId")
	List<Order> lockAll(Collection<Long> orderIds);
}
