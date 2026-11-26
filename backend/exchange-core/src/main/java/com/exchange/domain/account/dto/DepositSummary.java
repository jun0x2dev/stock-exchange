package com.exchange.domain.account.dto;

import java.time.Instant;

/** 운영자가 보는 입금·입고 한 건. {@code symbol}이 있으면 {@code amount}는 수량이다. */
public record DepositSummary(long depositId, long accountId, String owner, String symbol, long amount, Instant createdAt) {
}
