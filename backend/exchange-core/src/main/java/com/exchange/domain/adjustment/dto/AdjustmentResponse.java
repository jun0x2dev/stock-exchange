package com.exchange.domain.adjustment.dto;

import java.time.Instant;

import com.exchange.domain.account.entity.Deposit;
import com.exchange.domain.adjustment.entity.AdjustmentStatus;
import com.exchange.domain.adjustment.entity.LedgerAdjustment;
import com.exchange.domain.adjustment.repository.LedgerAdjustmentRepository.Row;

/** 원장 정정 요청 한 건과 대상 전표. {@code symbol}이 있으면 {@code amount}는 수량이다. 사람은 로그인 아이디로 보인다. */
public record AdjustmentResponse(long adjustmentId, long depositId, long accountId, String owner, String symbol,
		long amount, String reason, AdjustmentStatus status, String requester, String decider, Instant createdAt,
		Instant decidedAt) {

	/** 엔티티에서 만든다. */
	public static AdjustmentResponse from(Row row) {
		LedgerAdjustment adjustment = row.adjustment();
		Deposit deposit = row.deposit();
		return new AdjustmentResponse(adjustment.getAdjustmentId(), deposit.getDepositId(), deposit.getAccountId(),
			row.owner(), deposit.getSymbol(), deposit.getAmount(), adjustment.getReason(), adjustment.getStatus(),
			row.requester(), row.decider(), adjustment.getCreatedAt(), adjustment.getDecidedAt());
	}
}
