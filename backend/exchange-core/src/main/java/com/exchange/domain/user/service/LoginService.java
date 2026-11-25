package com.exchange.domain.user.service;

import org.springframework.security.core.userdetails.UserDetailsService;

import com.exchange.domain.user.dto.LoginUser;

/** 로그인 사용자 조회와 로그인 결과 기록. */
public interface LoginService extends UserDetailsService {

	/** 로그인 성공을 기록하고 실패 횟수를 지운다. */
	void onSuccess(LoginUser user);

	/** 로그인 실패를 기록한다. 이미 잠긴 계정의 실패는 횟수에 더하지 않는다. */
	void onFailure(String loginId, boolean alreadyLocked);
}
