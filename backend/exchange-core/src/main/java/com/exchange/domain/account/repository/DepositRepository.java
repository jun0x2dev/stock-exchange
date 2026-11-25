package com.exchange.domain.account.repository;

import java.util.Optional;

import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;

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

	/** 멱등키로 찾는다. */
	Optional<Deposit> findByAccountIdAndIdempotencyKey(long accountId, String idempotencyKey);
}
