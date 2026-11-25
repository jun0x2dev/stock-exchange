package com.exchange.global.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** API 에러 코드와 HTTP 상태, 사용자 메시지를 관리한다. */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	// 계정 존재 여부와 잠금 여부를 구분해 알려 주지 않는다.
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 맞지 않거나 잠긴 계정입니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요."),

	ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다."),
	INSUFFICIENT_CASH(HttpStatus.UNPROCESSABLE_CONTENT, "주문 가능 금액이 부족합니다."),
	INSUFFICIENT_QUANTITY(HttpStatus.UNPROCESSABLE_CONTENT, "팔 수 있는 수량이 부족합니다."),
	IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "이미 다른 요청에 사용한 멱등키입니다."),

	STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다."),
	STOCK_HALTED(HttpStatus.UNPROCESSABLE_CONTENT, "거래가 정지된 종목입니다."),
	INVALID_TICK_SIZE(HttpStatus.UNPROCESSABLE_CONTENT, "호가 단위에 맞지 않는 가격입니다."),
	PRICE_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_CONTENT, "상한가와 하한가 사이의 가격을 입력하세요."),

	ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
	ORDER_NOT_CANCELABLE(HttpStatus.CONFLICT, "이미 체결되었거나 취소된 주문입니다."),

	DEPOSIT_NOT_FOUND(HttpStatus.NOT_FOUND, "입금·입고 기록을 찾을 수 없습니다."),
	ADJUSTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "정정 요청을 찾을 수 없습니다."),
	ADJUSTMENT_ALREADY_REQUESTED(HttpStatus.CONFLICT, "이미 정정을 요청했거나 정정한 전표입니다."),
	ADJUSTMENT_ALREADY_DECIDED(HttpStatus.CONFLICT, "이미 승인하거나 거절한 요청입니다."),
	ADJUSTMENT_SELF_DECISION(HttpStatus.FORBIDDEN, "본인이 요청한 정정은 승인하거나 거절할 수 없습니다."),
	ADJUSTMENT_NOT_REVERSIBLE(HttpStatus.UNPROCESSABLE_CONTENT, "이미 썼거나 주문에 묶여 되돌릴 잔고가 부족합니다.");

	private final HttpStatus status;
	private final String message;
}
