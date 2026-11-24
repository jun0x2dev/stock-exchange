package com.exchange.domain.audit.service.impl;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.exchange.domain.audit.entity.AuditLog;
import com.exchange.domain.audit.repository.AuditLogRepository;
import com.exchange.domain.audit.service.AuditService;
import com.exchange.domain.user.dto.LoginUser;

import lombok.RequiredArgsConstructor;

/** 현재 요청에서 행위자와 IP를 찾아 감사 로그를 저장한다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

	private final AuditLogRepository auditLogRepository;

	@Override
	@Transactional
	public void recordSuccess(String action, String target) {
		auditLogRepository.save(AuditLog.of(currentUserId(), currentIp(), action, target, "SUCCESS"));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailure(String action, String result) {
		auditLogRepository.save(AuditLog.of(currentUserId(), currentIp(), action, null, result));
	}

	@Override
	@Transactional
	public void recordLogin(Long userId, boolean success) {
		auditLogRepository.save(AuditLog.of(userId, currentIp(), "LOGIN", null, success ? "SUCCESS" : "LOGIN_FAILED"));
	}

	// 로그인한 사용자를 찾는다. 로그인하지 않았으면 null이다.
	private static Long currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null && authentication.getPrincipal() instanceof LoginUser user ? user.userId() : null;
	}

	// 요청 IP를 찾는다. 위조할 수 있는 X-Forwarded-For는 신뢰하지 않는다.
	private static String currentIp() {
		return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
			? attributes.getRequest().getRemoteAddr() : null;
	}
}
