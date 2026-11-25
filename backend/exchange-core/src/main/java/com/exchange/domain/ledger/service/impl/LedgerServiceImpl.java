package com.exchange.domain.ledger.service.impl;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.account.service.AccountAccess;
import com.exchange.domain.ledger.dto.LedgerResponse;
import com.exchange.domain.ledger.entity.CashLedger;
import com.exchange.domain.ledger.entity.LedgerRef;
import com.exchange.domain.ledger.entity.LedgerType;
import com.exchange.domain.ledger.entity.PositionLedger;
import com.exchange.domain.ledger.entity.RefType;
import com.exchange.domain.ledger.repository.CashLedgerRepository;
import com.exchange.domain.ledger.repository.PositionLedgerRepository;
import com.exchange.domain.ledger.service.LedgerService;
import com.exchange.global.response.CursorPage;

import lombok.RequiredArgsConstructor;

/** 원장 서비스 구현. 기록 메서드는 호출한 쪽의 트랜잭션이 없으면 실패한다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LedgerServiceImpl implements LedgerService {

	private final CashLedgerRepository cashLedgerRepository;
	private final PositionLedgerRepository positionLedgerRepository;
	private final AccountAccess accountAccess;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerRef open(LedgerType type, RefType refType, long refId) {
		return new LedgerRef(cashLedgerRepository.nextJournalId(), type, refType, refId);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void cash(LedgerRef ref, long accountId, long cashDelta, long reservedDelta) {
		cashLedgerRepository.save(CashLedger.of(ref, accountId, cashDelta, reservedDelta));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void stock(LedgerRef ref, long accountId, String symbol, long quantityDelta, long reservedDelta) {
		positionLedgerRepository.save(PositionLedger.of(ref, accountId, symbol, quantityDelta, reservedDelta));
	}

	@Override
	public CursorPage<LedgerResponse> findCash(long userId, long accountId, Long cursor, int size) {
		accountAccess.requireOwned(userId, accountId);
		List<LedgerResponse> rows = cashLedgerRepository
			.findByAccountIdAndLedgerIdLessThanOrderByLedgerIdDesc(accountId,
				cursor == null ? Long.MAX_VALUE : cursor, Limit.of(size + 1))
			.stream().map(LedgerResponse::from).toList();
		return CursorPage.of(rows, size, LedgerResponse::ledgerId);
	}
}
