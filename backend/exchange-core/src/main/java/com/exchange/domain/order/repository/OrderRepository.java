package com.exchange.domain.order.repository;

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

import jakarta.persistence.LockModeType;

/** 주문 저장소. */
public interface OrderRepository extends Repository<Order, Long> {

	/** 호가창 한 줄. */
	interface PriceLevel {

		/** 가격. */
		long getPrice();

		/** 남은 수량의 합. */
		long getQuantity();
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

	/** 매수 호가를 높은 가격부터 조회한다. */
	@Query(nativeQuery = true, value = """
		SELECT price AS price, SUM(quantity - filled_quantity) AS quantity FROM orders
		 WHERE symbol = :symbol AND side = 'BUY' AND status IN ('OPEN', 'PARTIALLY_FILLED')
		 GROUP BY price ORDER BY price DESC LIMIT :depth
		""")
	List<PriceLevel> findBids(String symbol, int depth);

	/** 매도 호가를 낮은 가격부터 조회한다. */
	@Query(nativeQuery = true, value = """
		SELECT price AS price, SUM(quantity - filled_quantity) AS quantity FROM orders
		 WHERE symbol = :symbol AND side = 'SELL' AND status IN ('OPEN', 'PARTIALLY_FILLED')
		 GROUP BY price ORDER BY price LIMIT :depth
		""")
	List<PriceLevel> findAsks(String symbol, int depth);

	/** 엔진이 이 순번으로 접수한 주문인지 확인한다. */
	boolean existsByOrderIdAndEngineSeq(long orderId, long engineSeq);

	/** 주문이 이 상태인지 확인한다. */
	boolean existsByOrderIdAndStatus(long orderId, OrderStatus status);

	/** 주문을 낸 계좌들. 잠금 순서를 지키려고 주문을 잠그기 전에 계좌부터 알아낸다. */
	@Query("SELECT DISTINCT o.accountId FROM Order o WHERE o.orderId IN :orderIds")
	List<Long> findAccountIds(Collection<Long> orderIds);

	/** 주문 ID 순으로 잠근다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.orderId IN :orderIds ORDER BY o.orderId")
	List<Order> lockAll(Collection<Long> orderIds);
}
