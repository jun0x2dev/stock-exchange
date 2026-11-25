package com.exchange.domain.bot.service;

/** 시세를 따라 호가를 내고 가끔 거래하는 봇. 일반 사용자와 같은 계좌와 주문 규칙을 쓴다. */
public interface QuoteBotService {

	/** 시세 양쪽에 호가를 맞춘다. 이미 맞는 호가는 건드리지 않는다. */
	void quote(String symbol, long price);

	/** 낸 호가를 모두 거둔다. */
	void withdraw(String symbol);

	/** 최우선 호가로 소량 거래한다. 상대 호가가 없으면 아무 일도 하지 않는다. */
	void trade(String symbol, boolean buy, long quantity);
}
