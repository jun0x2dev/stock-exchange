package com.exchange.domain.user.service.impl;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.audit.service.AuditService;
import com.exchange.domain.user.dto.LoginUser;
import com.exchange.domain.user.entity.User;
import com.exchange.domain.user.repository.UserRepository;
import com.exchange.domain.user.service.LoginService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 로그인 실패가 이어지면 계정을 잠근다. */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {

	private static final int MAX_FAILURES = 5;
	private static final int LOCK_MINUTES = 15;

	private final UserRepository userRepository;
	private final AuditService auditService;

	@Override
	public UserDetails loadUserByUsername(String loginId) {
		return userRepository.findByLoginId(loginId)
			.map(LoginUser::from)
			.orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
	}

	@Override
	@Transactional
	public void onSuccess(LoginUser user) {
		userRepository.clearFailures(user.userId());
		auditService.recordLogin(user.userId(), true);
	}

	@Override
	@Transactional
	public void onFailure(String loginId, boolean alreadyLocked) {
		if (!alreadyLocked) {
			userRepository.recordFailure(loginId, MAX_FAILURES, LOCK_MINUTES);
		}
		// 입력한 아이디는 남기지 않는다. 아이디 칸에 비밀번호를 잘못 넣는 경우가 있다.
		Long userId = userRepository.findByLoginId(loginId).map(User::getUserId).orElse(null);
		auditService.recordLogin(userId, false);
		log.warn("로그인 실패: userId={}, locked={}", userId, alreadyLocked);
	}
}
