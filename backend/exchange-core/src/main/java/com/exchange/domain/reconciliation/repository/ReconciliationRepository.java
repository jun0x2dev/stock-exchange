package com.exchange.domain.reconciliation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.exchange.domain.reconciliation.entity.ReconciliationRun;
import com.exchange.domain.reconciliation.entity.ReconciliationViolation;

/** 대사 이력 저장소. 수정·삭제 메서드를 두지 않는다. */
public interface ReconciliationRepository extends Repository<ReconciliationRun, Long> {

	/** 한 번에 저장하는 불일치 행의 최대 수. 건수는 모두 센다. */
	int MAX_STORED_VIOLATIONS = 1000;

	/** 대사는 한 번에 하나만 실행한다. 트랜잭션이 끝나면 풀린다. */
	@Query(nativeQuery = true, value = "SELECT pg_try_advisory_xact_lock(7302003)")
	boolean tryLock();

	/** 며칠 전 날짜의 정기 대사가 이미 있는지 확인한다. 날짜는 DB 시계의 한국 시간으로 정한다. */
	@Query(nativeQuery = true, value = """
		SELECT EXISTS (SELECT 1 FROM reconciliation_run WHERE trigger = 'SCHEDULED'
		   AND business_date = CAST(timezone('Asia/Seoul', now()) AS date) - :daysBack)
		""")
	boolean existsScheduled(int daysBack);

	/**
	 * 불변식 대사 뷰를 한 문장으로 읽어 실행 이력과 불일치를 함께 저장하고 실행 ID를 반환한다.
	 *
	 * <p>한 문장이라 체결이 반영되는 중에도 한 시점의 잔고와 원장을 비교한다. 감사 로그 검증 결과는 따로 받는다.
	 */
	@Query(nativeQuery = true, value = """
		WITH found AS (
		    SELECT invariant, detail FROM invariant_violation
		), run AS (
		    INSERT INTO reconciliation_run (business_date, trigger, requested_by, status, invariant_violations,
		                                    audit_problems, audit_checked, started_at)
		    SELECT CAST(timezone('Asia/Seoul', now()) AS date) - :daysBack, :trigger, CAST(:userId AS BIGINT),
		           CASE WHEN COUNT(*) = 0 AND :auditProblems = 0 THEN 'PASSED' ELSE 'FAILED' END,
		           COUNT(*), :auditProblems, :auditChecked, now()
		      FROM found
		    RETURNING run_id
		), stored AS (
		    INSERT INTO reconciliation_violation (run_id, kind, detail)
		    SELECT run.run_id, found.invariant, left(found.detail, 200)
		      FROM run, (SELECT * FROM found ORDER BY invariant, detail LIMIT 1000) found
		)
		SELECT run_id FROM run
		""")
	long record(String trigger, Long userId, int daysBack, int auditProblems, long auditChecked);

	/** 감사 로그 검증에서 찾은 문제를 남긴다. */
	@Modifying
	@Query(nativeQuery = true, value = """
		INSERT INTO reconciliation_violation (run_id, kind, detail) VALUES (:runId, 'AUDIT', left(:detail, 200))
		""")
	int recordAuditProblem(long runId, String detail);

	/** 한 건. */
	Optional<ReconciliationRun> findByRunId(long runId);

	/** 커서보다 오래된 실행을 최신순으로 조회한다. */
	List<ReconciliationRun> findByRunIdLessThanOrderByRunIdDesc(long cursor, Limit limit);

	/** 실행의 불일치 내역. */
	@Query("SELECT v FROM ReconciliationViolation v WHERE v.runId = :runId ORDER BY v.violationId")
	List<ReconciliationViolation> findViolations(long runId, Limit limit);
}
