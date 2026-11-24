package com.exchange.global.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** API 에러 코드와 HTTP 상태, 사용자 메시지를 관리한다. */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");

	private final HttpStatus status;
	private final String message;
}
