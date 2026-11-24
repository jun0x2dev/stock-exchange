package com.exchange.domain.execution.service;

import java.util.List;

import com.exchange.domain.execution.dto.ExecutionResponse;
import com.exchange.domain.execution.dto.TradeResponse;
import com.exchange.engine.EngineEvent;
import com.exchange.global.response.CursorPage;

/** 엔진 결과를 DB에 반영하고 체결을 조회한다. */
public interface ExecutionService {

	/** 명령 하나의 엔진 결과를 한 트랜잭션에서 반영한다. 순번이 이어지지 않으면 실패한다. */
	void settle(String symbol, List<EngineEvent> events);

	/** 종목의 최근 체결. */
	List<TradeResponse> recentTrades(String symbol);

	/** 내 계좌의 체결 내역. */
	CursorPage<ExecutionResponse> findMine(long userId, long accountId, Long cursor, int size);
}
