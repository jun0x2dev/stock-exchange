package com.exchange.domain.order.exception;

import com.exchange.global.error.BusinessException;
import com.exchange.global.error.ErrorCode;

/** 주문 처리 중 업무 규칙을 어겼을 때 던지는 예외. */
public class OrderException extends BusinessException {

	/** 응답에 쓸 에러 코드로 예외를 만든다. */
	public OrderException(ErrorCode errorCode) {
		super(errorCode);
	}
}
