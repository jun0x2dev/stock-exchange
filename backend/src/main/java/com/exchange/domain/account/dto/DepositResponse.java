package com.exchange.domain.account.dto;

/** 입금·입고 결과. 같은 멱등키로 다시 요청하면 같은 ID가 반환된다. */
public record DepositResponse(long depositId) {
}
