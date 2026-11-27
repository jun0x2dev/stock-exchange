package com.exchange.domain.fds.dto;

import java.time.Instant;

import com.exchange.domain.fds.entity.FdsAlert;
import com.exchange.domain.fds.entity.FdsRule;

/** 이상거래 경보 한 건과 계좌 주인의 아이디. */
public record FdsAlertResponse(long alertId, FdsRule rule, long accountId, String owner, String symbol, Long orderId,
		String evidence, Instant detectedAt) {

	/** 엔티티에서 만든다. */
	public static FdsAlertResponse of(FdsAlert alert, String owner) {
		return new FdsAlertResponse(alert.getAlertId(), alert.getRule(), alert.getAccountId(), owner, alert.getSymbol(),
			alert.getOrderId(), alert.getEvidence(), alert.getDetectedAt());
	}
}
