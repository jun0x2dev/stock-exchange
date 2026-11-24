package com.exchange.domain.account.service;

/** 계좌 소유자 검증. 계좌를 다루는 모든 기능이 거친다. */
public interface AccountAccess {

	/** 사용자의 계좌가 아니면 존재 여부를 숨기고 찾을 수 없다고 응답한다. */
	void requireOwned(long userId, long accountId);
}
