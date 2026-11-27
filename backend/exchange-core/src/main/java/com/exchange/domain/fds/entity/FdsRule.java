package com.exchange.domain.fds.entity;

/** 이상거래 탐지 규칙. */
public enum FdsRule {
	/** 허수성 호가: 체결 의사 없이 큰 주문을 냈다가, 반대편에서 거래한 뒤 바로 취소한다. */
	SPOOFING
}
