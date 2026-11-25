package com.exchange.domain.order.entity;

import java.util.Set;

/** 주문 상태. ACCEPTED는 저장과 예약 완료, OPEN은 호가창 등록을 뜻한다. */
public enum OrderStatus {

	ACCEPTED, OPEN, PARTIALLY_FILLED, FILLED, CANCELED;

	/** 아직 체결될 수 있는 상태. */
	public static final Set<OrderStatus> ACTIVE = Set.of(ACCEPTED, OPEN, PARTIALLY_FILLED);

	/** 호가창에 올라가 있는 상태. */
	public static final Set<OrderStatus> ON_BOOK = Set.of(OPEN, PARTIALLY_FILLED);

	/** 아직 체결될 수 있는지 확인한다. */
	public boolean isActive() {
		return ACTIVE.contains(this);
	}
}
