package com.exchange.domain.ledger.dto;

import java.time.Instant;

import com.exchange.domain.ledger.entity.CashLedger;
import com.exchange.domain.ledger.entity.LedgerType;

/** 현금 원장 한 건. */
public record LedgerResponse(long ledgerId, LedgerType type, long cashDelta, long reservedDelta, Instant createdAt) {

	/** 엔티티에서 만든다. */
	public static LedgerResponse from(CashLedger ledger) {
		return new LedgerResponse(ledger.getLedgerId(), ledger.getType(), ledger.getCashDelta(),
			ledger.getReservedDelta(), ledger.getCreatedAt());
	}
}
