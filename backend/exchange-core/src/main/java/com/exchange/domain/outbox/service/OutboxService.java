package com.exchange.domain.outbox.service;

import com.exchange.engine.EngineCommand;

/** 엔진에 보낼 명령을 저장한다. */
public interface OutboxService {

	/** 주문을 바꾸는 트랜잭션 안에서 명령을 저장한다. 커밋되면 발행기가 보낸다. */
	void append(EngineCommand.Type type, String symbol, long orderId);
}
