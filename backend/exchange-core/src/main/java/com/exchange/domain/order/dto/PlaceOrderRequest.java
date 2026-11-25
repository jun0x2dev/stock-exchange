package com.exchange.domain.order.dto;

import com.exchange.domain.order.entity.OrderType;
import com.exchange.engine.Side;
import com.exchange.global.util.RequestHash;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 주문 접수 요청. */
public record PlaceOrderRequest(@NotNull Long accountId, @NotBlank @Size(max = 12) String symbol, @NotNull Side side,
		@NotNull OrderType orderType, @NotNull @Positive @Max(1_000_000_000) Long price,
		@NotNull @Positive @Max(1_000_000) Long quantity) {

	/** 같은 멱등키로 온 요청이 같은 내용인지 비교할 해시. */
	public String hash() {
		return RequestHash.of(accountId, symbol, side, orderType, price, quantity);
	}
}
