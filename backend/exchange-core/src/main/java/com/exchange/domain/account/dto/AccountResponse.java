package com.exchange.domain.account.dto;

import com.exchange.domain.account.entity.Account;

/** 계좌 잔고. */
public record AccountResponse(long accountId, long cashBalance, long reservedCash, long availableCash) {

	/** 엔티티에서 만든다. */
	public static AccountResponse from(Account account) {
		return new AccountResponse(account.getAccountId(), account.getCashBalance(), account.getReservedCash(),
			account.getCashBalance() - account.getReservedCash());
	}
}
