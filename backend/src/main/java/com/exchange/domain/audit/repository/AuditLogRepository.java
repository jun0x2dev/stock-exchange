package com.exchange.domain.audit.repository;

import org.springframework.data.repository.Repository;

import com.exchange.domain.audit.entity.AuditLog;

/** 감사 로그 저장소. 수정·삭제 메서드를 두지 않는다. */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

	/** 감사 로그를 추가한다. */
	AuditLog save(AuditLog log);
}
