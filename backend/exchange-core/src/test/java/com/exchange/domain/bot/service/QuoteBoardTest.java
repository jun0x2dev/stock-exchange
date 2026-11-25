package com.exchange.domain.bot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.exchange.market.Quote;

/** 오래된 시세를 쓰지 않는지 검증한다. */
class QuoteBoardTest {

	private final AtomicLong now = new AtomicLong();
	private final QuoteBoard board = new QuoteBoard(now::get);

	@Test
	@DisplayName("받은 적이 없거나 5초 넘게 새 시세가 없으면 쓰지 않는다")
	void dropsStaleQuote() {
		assertThat(board.fresh("T")).isEmpty();
		board.update(new Quote("T", 71_000));
		now.addAndGet(QuoteBoard.STALE_AFTER.toNanos());
		assertThat(board.fresh("T")).hasValue(71_000);
		now.incrementAndGet();
		assertThat(board.fresh("T")).isEmpty();
		board.update(new Quote("T", 71_100));
		assertThat(board.fresh("T")).hasValue(71_100);
	}
}
