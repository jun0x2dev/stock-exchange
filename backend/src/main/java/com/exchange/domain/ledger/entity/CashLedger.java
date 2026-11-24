package com.exchange.domain.ledger.entity;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 현금 원장. INSERT만 하며 변경 메서드를 두지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashLedger {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long ledgerId;

	private long journalId;

	private long accountId;

	@Enumerated(EnumType.STRING)
	private LedgerType type;

	private long cashDelta;

	private long reservedDelta;

	@Enumerated(EnumType.STRING)
	private RefType refType;

	private long refId;

	private Instant createdAt;

	/** 잔고 증감과 예약 증감을 기록한다. */
	public static CashLedger of(LedgerRef ref, long accountId, long cashDelta, long reservedDelta) {
		CashLedger ledger = new CashLedger();
		ledger.journalId = ref.journalId();
		ledger.accountId = accountId;
		ledger.type = ref.type();
		ledger.cashDelta = cashDelta;
		ledger.reservedDelta = reservedDelta;
		ledger.refType = ref.refType();
		ledger.refId = ref.refId();
		ledger.createdAt = Instant.now();
		return ledger;
	}
}
