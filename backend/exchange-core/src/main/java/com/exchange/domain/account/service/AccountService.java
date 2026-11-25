package com.exchange.domain.account.service;

import java.util.List;

import com.exchange.domain.account.dto.AccountResponse;
import com.exchange.domain.account.dto.DepositRequest;
import com.exchange.domain.account.dto.DepositResponse;
import com.exchange.domain.account.dto.PositionResponse;
import com.exchange.domain.account.dto.StockDepositRequest;

/** 잔고 조회와 모의 입금·입고. */
public interface AccountService {

	/** 내 계좌 목록. */
	List<AccountResponse> findMine(long userId);

	/** 내 계좌의 잔고. */
	AccountResponse get(long userId, long accountId);

	/** 내 계좌의 보유 종목과 손익. */
	List<PositionResponse> findPositions(long userId, long accountId);

	/** 내 계좌에 모의 입금한다. */
	DepositResponse deposit(long userId, long accountId, String idempotencyKey, DepositRequest request);

	/** 운영자가 고객 계좌에 모의 주식을 입고한다. */
	DepositResponse depositStock(long accountId, String idempotencyKey, StockDepositRequest request);

	/** 입금·입고 기록이 있는지 확인한다. */
	void requireDeposit(long depositId);

	/** 승인된 정정에 따라 입금·입고를 반대 전표로 되돌린다. 승인 트랜잭션 안에서만 부른다. */
	void reverseDeposit(long depositId, long adjustmentId);
}
