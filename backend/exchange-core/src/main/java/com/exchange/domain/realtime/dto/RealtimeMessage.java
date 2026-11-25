package com.exchange.domain.realtime.dto;

import java.util.List;

import com.exchange.domain.execution.dto.TradeResponse;
import com.exchange.engine.BookSnapshot;

/** 화면에 보내는 실시간 메시지. ACCOUNT는 내 주문이나 잔고가 바뀌었다는 알림이며 내용은 다시 조회한다. */
public record RealtimeMessage(Type type, BookSnapshot book, List<TradeResponse> trades) {

	/** 메시지 종류. */
	public enum Type {
		MARKET, ACCOUNT
	}

	/** 호가와 최근 체결. */
	public static RealtimeMessage market(BookSnapshot book, List<TradeResponse> trades) {
		return new RealtimeMessage(Type.MARKET, book, trades);
	}

	/** 내 주문이나 잔고가 바뀌었다는 알림. */
	public static RealtimeMessage account() {
		return new RealtimeMessage(Type.ACCOUNT, null, null);
	}
}
