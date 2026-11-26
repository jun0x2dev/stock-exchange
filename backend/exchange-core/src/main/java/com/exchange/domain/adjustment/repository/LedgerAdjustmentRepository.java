package com.exchange.domain.adjustment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.exchange.domain.account.entity.Deposit;
import com.exchange.domain.adjustment.entity.LedgerAdjustment;

/** 원장 정정 요청 저장소. */
public interface LedgerAdjustmentRepository extends Repository<LedgerAdjustment, Long> {

	/** 정정 요청, 대상 전표, 계좌 주인·요청자·결정자의 아이디. 결정 전이면 결정자는 null이다. */
	record Row(LedgerAdjustment adjustment, Deposit deposit, String owner, String requester, String decider) {
	}

	/** 같은 전표에 진행 중이거나 승인된 요청이 없을 때만 저장하고 ID를 반환한다. */
	@Query(nativeQuery = true, value = """
		INSERT INTO ledger_adjustment (deposit_id, reason, requested_by) VALUES (:depositId, :reason, :userId)
		ON CONFLICT (deposit_id) WHERE status <> 'REJECTED' DO NOTHING
		RETURNING adjustment_id
		""")
	Optional<Long> insertIfAbsent(long depositId, String reason, long userId);

	/** 아직 결정되지 않았고 본인이 요청하지 않은 건만 결정한다. 동시에 승인해도 한 번만 성공한다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(nativeQuery = true, value = """
		UPDATE ledger_adjustment SET status = :status, decided_by = :userId, decided_at = clock_timestamp()
		 WHERE adjustment_id = :adjustmentId AND status = 'REQUESTED' AND requested_by <> :userId
		""")
	int decide(long adjustmentId, String status, long userId);

	/** 한 건. */
	@Query("""
		SELECT new com.exchange.domain.adjustment.repository.LedgerAdjustmentRepository$Row(a, d, o.loginId, r.loginId, x.loginId)
		  FROM LedgerAdjustment a JOIN Deposit d ON d.depositId = a.depositId
		  JOIN Account ac ON ac.accountId = d.accountId JOIN User o ON o.userId = ac.userId
		  JOIN User r ON r.userId = a.requestedBy LEFT JOIN User x ON x.userId = a.decidedBy WHERE a.adjustmentId = :adjustmentId
		""")
	Optional<Row> findRow(long adjustmentId);

	/** 커서보다 오래된 요청을 최신순으로 조회한다. */
	@Query("""
		SELECT new com.exchange.domain.adjustment.repository.LedgerAdjustmentRepository$Row(a, d, o.loginId, r.loginId, x.loginId)
		  FROM LedgerAdjustment a JOIN Deposit d ON d.depositId = a.depositId
		  JOIN Account ac ON ac.accountId = d.accountId JOIN User o ON o.userId = ac.userId
		  JOIN User r ON r.userId = a.requestedBy LEFT JOIN User x ON x.userId = a.decidedBy
		 WHERE a.adjustmentId < :cursor ORDER BY a.adjustmentId DESC
		""")
	List<Row> findRows(long cursor, Limit limit);
}
