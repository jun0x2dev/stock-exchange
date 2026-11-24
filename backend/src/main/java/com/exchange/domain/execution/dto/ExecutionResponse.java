package com.exchange.domain.execution.dto;

import java.time.Instant;

import com.exchange.domain.execution.repository.ExecutionRepository.MyExecution;
import com.exchange.engine.Side;

/** 내 체결 한 건. */
public record ExecutionResponse(long executionId, long orderId, String symbol, Side side, long price, long quantity,
		Instant executedAt) {

	/** 조회 결과에서 만든다. */
	public static ExecutionResponse from(MyExecution row) {
		return new ExecutionResponse(row.getExecutionId(), row.getOrderId(), row.getSymbol(),
			Side.valueOf(row.getSide()), row.getPrice(), row.getQuantity(), row.getExecutedAt());
	}
}
