package com.exchange.domain.account.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;

import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;

import com.exchange.domain.account.dto.DepositSummary;
import com.exchange.domain.account.entity.Deposit;

/** 입금·입고 기록 저장소. */
public interface DepositRepository extends Repository<Deposit, Long> {

	/** 멱등키가 처음일 때만 저장하고 ID를 반환한다. 이미 있으면 비어 있다. */
	@Query(nativeQuery = true, value = """
		INSERT INTO deposit (account_id, symbol, amount, idempotency_key, request_hash)
		VALUES (:accountId, :symbol, :amount, :key, :hash)
		ON CONFLICT (account_id, idempotency_key) DO NOTHING
		RETURNING deposit_id
		""")
	Optional<Long> insertIfAbsent(long accountId, String symbol, long amount, String key, String hash);

	/** ID로 찾는다. */
	Optional<Deposit> findByDepositId(long depositId);

	/** 커서보다 오래된 입금·입고를 최신순으로, 계좌 주인의 아이디와 함께 조회한다. */
	@Query("""
		SELECT new com.exchange.domain.account.dto.DepositSummary(d.depositId, d.accountId, u.loginId, d.symbol,
		       d.amount, d.createdAt)
		  FROM Deposit d JOIN Account a ON a.accountId = d.accountId JOIN User u ON u.userId = a.userId
		 WHERE d.depositId < :cursor ORDER BY d.depositId DESC
		""")
	List<DepositSummary> findSummaries(long cursor, Limit limit);

	/** 멱등키로 찾는다. */
	Optional<Deposit> findByAccountIdAndIdempotencyKey(long accountId, String idempotencyKey);
}
