package com.exchange.domain.execution.exception;

/** 엔진 결과와 DB가 어긋났다. 다시 시도해도 풀리지 않으므로 바로 종목을 멈춘다. */
public class EngineMismatchException extends RuntimeException {

	/** 어긋난 내용으로 예외를 만든다. */
	public EngineMismatchException(String message) {
		super(message);
	}
}
