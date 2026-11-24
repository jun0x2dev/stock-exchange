package com.exchange.domain.audit.entity;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 감사 로그 한 건. INSERT만 한다. */
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long auditId;

	private Long userId;

	private String ip;

	private String action;

	private String target;

	private String result;

	private Instant createdAt;

	/** 감사 로그를 만든다. 컬럼 길이를 넘는 경로는 잘라 기록 자체가 실패하지 않게 한다. */
	public static AuditLog of(Long userId, String ip, String action, String target, String result) {
		AuditLog log = new AuditLog();
		log.userId = userId;
		log.ip = ip;
		log.action = action.length() > 100 ? action.substring(0, 100) : action;
		log.target = target;
		log.result = result;
		log.createdAt = Instant.now();
		return log;
	}
}
