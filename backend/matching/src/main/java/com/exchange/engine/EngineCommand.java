package com.exchange.engine;

/** 엔진에 보내는 명령. 명령 ID로 중복을 구분한다. 취소는 주문 ID만 쓴다. */
public record EngineCommand(long commandId, Type type, String symbol, long orderId, long accountId, Side side,
		long price, long quantity) {

	/** 명령의 종류. */
	public enum Type {
		PLACE, CANCEL
	}
}
