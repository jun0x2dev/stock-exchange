package com.exchange.domain.account.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.exchange.domain.account.entity.Position;
import com.exchange.domain.account.entity.PositionId;

import jakarta.persistence.LockModeType;

/** 보유 종목 저장소. */
public interface PositionRepository extends JpaRepository<Position, PositionId> {

	/** 계좌의 보유 종목. 전량 매도해 실현손익만 남은 종목도 포함한다. */
	@Query("SELECT p FROM Position p WHERE p.id.accountId = :accountId ORDER BY p.id.symbol")
	List<Position> findByAccountId(long accountId);

	/** 처음 사는 종목의 행을 만든다. 이미 있으면 그대로 둔다. */
	@Modifying(flushAutomatically = true)
	@Query(nativeQuery = true, value = """
		INSERT INTO position (account_id, symbol) VALUES (:accountId, :symbol) ON CONFLICT DO NOTHING
		""")
	int insertIfAbsent(long accountId, String symbol);

	/** 주식을 입고한다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Position p SET p.quantity = p.quantity + :quantity, p.totalCost = p.totalCost + :cost
		 WHERE p.id.accountId = :accountId AND p.id.symbol = :symbol
		""")
	int deposit(long accountId, String symbol, long quantity, long cost);

	/** 팔 수 있는 수량이 충분할 때만 예약한다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Position p SET p.reservedQuantity = p.reservedQuantity + :quantity
		 WHERE p.id.accountId = :accountId AND p.id.symbol = :symbol
		   AND p.quantity - p.reservedQuantity >= :quantity
		""")
	int reserve(long accountId, String symbol, long quantity);

	/** 데드락을 막으려고 계좌 ID 순으로 잠근다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Position p WHERE p.id.symbol = :symbol AND p.id.accountId IN :ids ORDER BY p.id.accountId")
	List<Position> lockAll(String symbol, Collection<Long> ids);
}
