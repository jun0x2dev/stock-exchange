package com.exchange.domain.account.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계좌. 예수금에는 예약금이 포함된다.
 *
 * <p>증감 메서드는 행을 잠그고 읽은 체결 반영에서만 쓴다. 예약과 입금은 조건부 UPDATE로 처리한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long accountId;

	private Long userId;

	@Enumerated(EnumType.STRING)
	private AccountType accountType;

	private long cashBalance;

	private long reservedCash;

	/** 고객 계좌를 만든다. */
	public static Account create(long userId) {
		Account account = new Account();
		account.userId = userId;
		account.accountType = AccountType.CUSTOMER;
		return account;
	}

	/** 매수 체결: 대금을 내고 해당 수량만큼 묶어 둔 예약금을 푼다. */
	public void payForBuy(long amount, long released) {
		cashBalance = Math.subtractExact(cashBalance, amount);
		reservedCash = Math.subtractExact(reservedCash, released);
	}

	/** 매도 체결: 대금을 받는다. */
	public void receive(long amount) {
		cashBalance = Math.addExact(cashBalance, amount);
	}

	/** 취소: 예약금을 푼다. */
	public void release(long amount) {
		reservedCash = Math.subtractExact(reservedCash, amount);
	}
}
