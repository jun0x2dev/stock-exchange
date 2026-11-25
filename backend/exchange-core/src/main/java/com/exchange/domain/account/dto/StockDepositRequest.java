package com.exchange.domain.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 모의 주식 입고 요청. */
public record StockDepositRequest(@NotBlank @Size(max = 12) String symbol,
		@NotNull @Positive @Max(1_000_000) Long quantity) {
}
