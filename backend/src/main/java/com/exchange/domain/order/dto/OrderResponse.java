package com.exchange.domain.order.dto;

import java.time.Instant;

import com.exchange.domain.order.entity.Order;
import com.exchange.domain.order.entity.OrderStatus;
import com.exchange.engine.Side;

/** 주문 조회 결과. */
public record OrderResponse(long orderId, long accountId, String symbol, Side side, long price, long quantity,
		long filledQuantity, OrderStatus status, boolean cancelRequested, Instant createdAt) {

	/** 엔티티에서 만든다. */
	public static OrderResponse from(Order order) {
		return new OrderResponse(order.getOrderId(), order.getAccountId(), order.getSymbol(), order.getSide(),
			order.getPrice(), order.getQuantity(), order.getFilledQuantity(), order.getStatus(),
			order.getCancelRequestedAt() != null, order.getCreatedAt());
	}
}
