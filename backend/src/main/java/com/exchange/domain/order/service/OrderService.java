package com.exchange.domain.order.service;

import com.exchange.domain.order.dto.OrderResponse;
import com.exchange.domain.order.dto.PlaceOrderRequest;
import com.exchange.global.response.CursorPage;

/** 주문 접수, 취소, 조회. */
public interface OrderService {

	/** 주문을 저장하고 자금이나 주식을 예약한다. 같은 멱등키로 다시 오면 기존 주문을 반환한다. */
	OrderResponse place(long userId, String idempotencyKey, PlaceOrderRequest request);

	/** 취소를 요청한다. 취소보다 체결이 먼저 처리될 수 있다. */
	OrderResponse cancel(long userId, long orderId);

	/** 내 주문 하나를 조회한다. */
	OrderResponse get(long userId, long orderId);

	/** 내 계좌의 주문을 최신순으로 조회한다. */
	CursorPage<OrderResponse> find(long userId, long accountId, boolean activeOnly, Long cursor, int size);
}
