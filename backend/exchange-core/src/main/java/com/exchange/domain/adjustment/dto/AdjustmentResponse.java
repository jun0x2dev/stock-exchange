package com.exchange.domain.adjustment.dto;

import java.time.Instant;

import com.exchange.domain.account.entity.Deposit;
import com.exchange.domain.adjustment.entity.AdjustmentStatus;
import com.exchange.domain.adjustment.entity.LedgerAdjustment;

/** 원장 정정 요청 한 건과 대상 전표. {@code symbol}이 있으면 {@code amount}는 수량이다. */
public record AdjustmentResponse(long adjustmentId, long depositId, long accountId, String symbol, long amount,
		String reason, AdjustmentStatus status, long requestedBy, Long decidedBy, Instant createdAt, Instant decidedAt) {

	/** 엔티티에서 만든다. */
	public static AdjustmentResponse of(LedgerAdjustment adjustment, Deposit deposit) {
		return new AdjustmentResponse(adjustment.getAdjustmentId(), deposit.getDepositId(), deposit.getAccountId(),
			deposit.getSymbol(), deposit.getAmount(), adjustment.getReason(), adjustment.getStatus(),
			adjustment.getRequestedBy(), adjustment.getDecidedBy(), adjustment.getCreatedAt(), adjustment.getDecidedAt());
	}
}
