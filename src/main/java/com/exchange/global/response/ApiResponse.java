package com.exchange.global.response;

import com.exchange.global.error.ErrorCode;

/** 모든 API가 공통으로 쓰는 응답 형식. */
public record ApiResponse<T>(boolean success, T data, Error error) {

	/** 실패 응답의 에러 코드와 메시지. */
	public record Error(String code, String message) {
	}

	/** 성공 응답을 만든다. */
	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null);
	}

	/** 에러 코드로 실패 응답을 만든다. */
	public static ApiResponse<Void> fail(ErrorCode code) {
		return new ApiResponse<>(false, null, new Error(code.name(), code.getMessage()));
	}
}
