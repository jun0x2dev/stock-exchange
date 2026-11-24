package com.exchange.domain.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 모의 입금 요청. 한 번에 10억 원까지 입금한다. */
public record DepositRequest(@NotNull @Positive @Max(1_000_000_000) Long amount) {
}
