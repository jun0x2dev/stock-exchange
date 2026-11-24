package com.exchange.domain.user.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.exchange.domain.user.dto.LoginUser;
import com.exchange.domain.user.dto.MeResponse;
import com.exchange.global.response.ApiResponse;

/** 로그인 상태 조회. 로그인과 로그아웃은 보안 필터가 처리한다. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	/** 로그인한 사용자를 반환한다. */
	@GetMapping("/me")
	ApiResponse<MeResponse> me(@AuthenticationPrincipal LoginUser user) {
		return ApiResponse.ok(MeResponse.from(user));
	}
}
