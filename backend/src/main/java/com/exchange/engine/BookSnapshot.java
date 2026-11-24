package com.exchange.engine;

import java.util.List;

/** 조회용 불변 호가창. 매수는 높은 가격부터, 매도는 낮은 가격부터 담는다. */
public record BookSnapshot(String symbol, long lastSeq, List<Level> bids, List<Level> asks) {

	/** 한 가격의 잔량 합계. */
	public record Level(long price, long quantity) {
	}

	/** 아직 주문이 없는 호가창. */
	public static BookSnapshot empty(String symbol) {
		return new BookSnapshot(symbol, 0, List.of(), List.of());
	}
}
