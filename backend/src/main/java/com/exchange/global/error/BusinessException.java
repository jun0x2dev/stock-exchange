package com.exchange.global.error;

import lombok.Getter;

/** 도메인별 예외의 상위 타입. 에러 코드로 응답을 결정한다. */
@Getter
public abstract class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	/** 에러 코드의 메시지를 예외 메시지로 쓴다. */
	protected BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
