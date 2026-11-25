package com.exchange.domain.ledger.repository;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.exchange.domain.ledger.entity.CashLedger;

/** 현금 원장 저장소. 수정·삭제 메서드를 두지 않는다. */
public interface CashLedgerRepository extends Repository<CashLedger, Long> {

	/** 원장을 추가한다. */
	CashLedger save(CashLedger ledger);

	/** 커서보다 오래된 기록을 최신순으로 조회한다. */
	List<CashLedger> findByAccountIdAndLedgerIdLessThanOrderByLedgerIdDesc(long accountId, long cursor, Limit limit);

	/** 새 전표 번호. */
	@Query(nativeQuery = true, value = "SELECT nextval('journal_seq')")
	long nextJournalId();
}
