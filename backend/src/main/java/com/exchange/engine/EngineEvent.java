package com.exchange.engine;

/** 매칭 엔진이 종목별 순번을 붙여 내보내는 결과. */
public sealed interface EngineEvent {

	/** 종목별로 1씩 증가하는 순번. */
	long seq();

	/** 주문이 엔진에 접수됐다. 순번이 시간 우선의 기준이 된다. */
	record Accepted(long seq, long orderId) implements EngineEvent {
	}

	/** 체결됐다. 가격은 호가창에 먼저 있던 주문의 가격이다. */
	record Traded(long seq, long buyOrderId, long sellOrderId, long price, long quantity) implements EngineEvent {
	}

	/** 주문의 남은 수량이 취소됐다. */
	record Canceled(long seq, long orderId) implements EngineEvent {
	}
}
