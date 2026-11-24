package com.exchange.domain.execution.dto;

import java.time.Instant;

import com.exchange.domain.execution.entity.Execution;

/** 공개 시세로 보여 주는 체결. */
public record TradeResponse(long seq, long price, long quantity, Instant executedAt) {

	/** 엔티티에서 만든다. */
	public static TradeResponse from(Execution execution) {
		return new TradeResponse(execution.getEventSeq(), execution.getPrice(), execution.getQuantity(),
			execution.getExecutedAt());
	}
}
