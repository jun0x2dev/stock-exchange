package com.exchange.domain.ledger.entity;

/** 한 전표의 번호와 근거. 같은 전표의 기록은 증감 합이 0이어야 한다. */
public record LedgerRef(long journalId, LedgerType type, RefType refType, long refId) {

	/** 같은 전표에서 종류만 바꾼다. */
	public LedgerRef as(LedgerType other) {
		return new LedgerRef(journalId, other, refType, refId);
	}
}
