package com.exchange.domain.account.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.account.exception.AccountException;
import com.exchange.domain.account.repository.AccountRepository;
import com.exchange.domain.account.service.AccountAccess;
import com.exchange.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

/** 계좌 소유자 검증 구현. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AccountAccessImpl implements AccountAccess {

	private final AccountRepository accountRepository;

	@Override
	public void requireOwned(long userId, long accountId) {
		if (!accountRepository.existsByAccountIdAndUserId(accountId, userId)) {
			throw new AccountException(ErrorCode.ACCOUNT_NOT_FOUND);
		}
	}
}
