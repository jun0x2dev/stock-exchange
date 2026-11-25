package com.exchange.domain.bot.service;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.exchange.market.Quote;

/** 종목별 최신 시세. 정해진 시간 동안 새 시세가 없으면 없는 것으로 본다. */
public class QuoteBoard {

	/** 이 시간이 지난 시세로는 호가를 내지 않는다. */
	static final Duration STALE_AFTER = Duration.ofSeconds(5);

	private final Map<String, Received> latest = new ConcurrentHashMap<>();
	private final LongSupplier nanoTime;

	/** 경과 시간을 잴 시계를 받는다. 벽시계는 보정되며 뒤로 갈 수 있어 쓰지 않는다. */
	public QuoteBoard(LongSupplier nanoTime) {
		this.nanoTime = nanoTime;
	}

	/** 시세를 받은 시각과 함께 기록한다. */
	public void update(Quote quote) {
		latest.put(quote.symbol(), new Received(quote.price(), nanoTime.getAsLong()));
	}

	/** 쓸 수 있는 시세. 없거나 오래됐으면 비어 있다. */
	public OptionalLong fresh(String symbol) {
		Received received = latest.get(symbol);
		if (received == null || nanoTime.getAsLong() - received.at() > STALE_AFTER.toNanos()) {
			return OptionalLong.empty();
		}
		return OptionalLong.of(received.price());
	}

	/** 가격과 받은 시각. */
	private record Received(long price, long at) {
	}
}
