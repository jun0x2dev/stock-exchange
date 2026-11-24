package com.exchange.domain.ledger.service;

import com.exchange.domain.ledger.dto.LedgerResponse;
import com.exchange.domain.ledger.entity.LedgerRef;
import com.exchange.domain.ledger.entity.LedgerType;
import com.exchange.domain.ledger.entity.RefType;
import com.exchange.global.response.CursorPage;

/** 복식부기 원장 기록과 조회. 기록은 잔고를 바꾸는 트랜잭션 안에서만 한다. */
public interface LedgerService {

	/** 새 전표를 시작한다. */
	LedgerRef open(LedgerType type, RefType refType, long refId);

	/** 현금 증감을 기록한다. */
	void cash(LedgerRef ref, long accountId, long cashDelta, long reservedDelta);

	/** 주식 증감을 기록한다. */
	void stock(LedgerRef ref, long accountId, String symbol, long quantityDelta, long reservedDelta);

	/** 로그인 사용자의 계좌 현금 원장을 조회한다. */
	CursorPage<LedgerResponse> findCash(long userId, long accountId, Long cursor, int size);
}
