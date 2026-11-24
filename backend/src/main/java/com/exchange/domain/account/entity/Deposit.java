package com.exchange.domain.account.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 모의 입금·주식 입고 기록. 멱등키로 중복을 막으며, 저장은 저장소의 INSERT로만 한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deposit {

	@Id
	private Long depositId;

	private long accountId;

	private String symbol;

	private long amount;

	private String idempotencyKey;

	private String requestHash;
}
