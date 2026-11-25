package com.exchange.domain.audit.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.exchange.domain.audit.entity.AuditChain;
import com.exchange.domain.audit.entity.AuditLog;

/** 감사 로그 체인 저장소. 수정·삭제 메서드를 두지 않는다. */
public interface AuditChainRepository extends Repository<AuditChain, Long> {

	/** 체인 한 칸과 그 감사 로그. 로그가 지워졌으면 null이다. */
	record Link(AuditChain chain, AuditLog log) {
	}

	/** 체인을 잇는다. */
	AuditChain save(AuditChain chain);

	/** 체인의 마지막 칸. */
	Optional<AuditChain> findFirstByOrderByChainSeqDesc();

	/** 번호 순으로 이어지는 칸들. */
	@Query("""
		SELECT new com.exchange.domain.audit.repository.AuditChainRepository$Link(c, a)
		  FROM AuditChain c LEFT JOIN AuditLog a ON a.auditId = c.auditId
		 WHERE c.chainSeq > :after ORDER BY c.chainSeq
		""")
	List<Link> findAfter(long after, Limit limit);

	/** 아직 체인에 넣지 않은 감사 로그. */
	@Query(nativeQuery = true, value = """
		SELECT a.* FROM audit_pending p JOIN audit_log a USING (audit_id) ORDER BY a.audit_id LIMIT :size
		""")
	List<AuditLog> findPending(int size);

	/** 체인에 넣은 감사 로그를 대기 목록에서 뺀다. */
	@Modifying
	@Query(nativeQuery = true, value = "DELETE FROM audit_pending WHERE audit_id IN (:auditIds)")
	int deletePending(Collection<Long> auditIds);

	/** 체인에 없는 감사 로그 중 지정한 시간보다 오래된 것의 수. 대기 목록이 조작돼도 드러난다. */
	@Query(nativeQuery = true, value = """
		SELECT COUNT(*) FROM audit_log a
		 WHERE a.created_at < clock_timestamp() - make_interval(secs => :seconds)
		   AND NOT EXISTS (SELECT 1 FROM audit_chain c WHERE c.audit_id = a.audit_id)
		""")
	long countUnchained(int seconds);

	/** 체인은 한 번에 하나의 작업자만 잇는다. */
	@Query(nativeQuery = true, value = "SELECT pg_try_advisory_xact_lock(7302002)")
	boolean tryLock();
}
