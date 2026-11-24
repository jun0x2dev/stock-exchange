package com.exchange.domain.ledger.repository;

import org.springframework.data.repository.Repository;

import com.exchange.domain.ledger.entity.PositionLedger;

/** 주식 원장 저장소. 수정·삭제 메서드를 두지 않는다. */
public interface PositionLedgerRepository extends Repository<PositionLedger, Long> {

	/** 원장을 추가한다. */
	PositionLedger save(PositionLedger ledger);
}
