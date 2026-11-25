package com.exchange.domain.user.dto;

import com.exchange.domain.user.entity.Role;

/** 로그인한 사용자 정보. */
public record MeResponse(String loginId, Role role) {

	/** 세션 사용자에서 만든다. */
	public static MeResponse from(LoginUser user) {
		return new MeResponse(user.getUsername(), user.role());
	}
}
