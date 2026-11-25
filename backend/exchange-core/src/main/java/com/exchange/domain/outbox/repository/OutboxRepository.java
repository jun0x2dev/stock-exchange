package com.exchange.domain.outbox.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.exchange.domain.outbox.entity.Outbox;
import com.exchange.engine.EngineCommand;

/** 보낼 명령 저장소. */
public interface OutboxRepository extends Repository<Outbox, Long> {

	/** 명령을 추가한다. */
	Outbox save(Outbox outbox);

	/** 아직 보내지 않은 명령을 순서대로 조회한다. 늦게 커밋된 작은 번호도 빠뜨리지 않도록 발행 여부로 찾는다. */
	@Query("""
		SELECT new com.exchange.engine.EngineCommand(b.commandId, b.type, b.symbol, o.orderId, o.accountId, o.side,
		       o.price, o.quantity)
		  FROM Outbox b JOIN Order o ON o.orderId = b.orderId
		 WHERE b.publishedAt IS NULL ORDER BY b.commandId
		""")
	List<EngineCommand> findUnpublished(Limit limit);

	/** 보낸 명령으로 표시한다. */
	@Modifying
	@Query(nativeQuery = true,
		value = "UPDATE outbox SET published_at = clock_timestamp() WHERE command_id IN (:commandIds)")
	int markPublished(Collection<Long> commandIds);

	/** 발행기가 여러 개 떠도 한 번에 하나만 보내도록 트랜잭션이 끝날 때까지 잠근다. */
	@Query(nativeQuery = true, value = "SELECT pg_try_advisory_xact_lock(7302001)")
	boolean tryLock();

	/** 발행 표시는 잃어도 다시 보내고 엔진이 중복을 버리므로, 이 트랜잭션만 디스크 동기화를 기다리지 않는다. */
	@Modifying
	@Query(nativeQuery = true, value = "SET LOCAL synchronous_commit TO OFF")
	void skipDiskSync();
}
