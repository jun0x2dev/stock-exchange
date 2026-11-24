package com.exchange.domain.account.entity;

/** 계좌 종류. EXTERNAL은 입출금·입고의 상대 계정이며 잔고 컬럼을 쓰지 않는다. */
public enum AccountType {
	CUSTOMER, EXTERNAL
}
