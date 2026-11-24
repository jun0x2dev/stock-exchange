package com.exchange.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.exchange.engine.EngineEvent.Accepted;
import com.exchange.engine.EngineEvent.Canceled;
import com.exchange.engine.EngineEvent.Traded;

/** 매칭 규칙과 결정성을 검증한다. */
class OrderBookTest {

	private final OrderBook book = new OrderBook("T", 0);

	@Test
	@DisplayName("가격이 맞지 않으면 체결하지 않고 호가창에 올린다")
	void restsWhenNotCrossed() {
		book.place(1, 10, Side.SELL, 101, 5);
		assertThat(book.place(2, 20, Side.BUY, 100, 3)).containsExactly(new Accepted(2, 2));
		assertThat(book.snapshot(10)).isEqualTo(new BookSnapshot("T", 2,
			List.of(new BookSnapshot.Level(100, 3)), List.of(new BookSnapshot.Level(101, 5))));
	}

	@Test
	@DisplayName("먼저 있던 주문의 가격으로 체결하고 남은 수량을 호가창에 올린다")
	void tradesAtMakerPrice() {
		book.place(1, 10, Side.SELL, 100, 5);
		assertThat(book.place(2, 20, Side.BUY, 105, 8)).containsExactly(new Accepted(2, 2), new Traded(3, 2, 1, 100, 5));
		assertThat(book.snapshot(10).bids()).containsExactly(new BookSnapshot.Level(105, 3));
		assertThat(book.snapshot(10).asks()).isEmpty();
	}

	@Test
	@DisplayName("유리한 가격부터, 같은 가격이면 먼저 온 주문부터 체결한다")
	void followsPriceTimePriority() {
		book.place(1, 10, Side.SELL, 102, 1);
		book.place(2, 11, Side.SELL, 101, 1);
		book.place(3, 12, Side.SELL, 101, 1);
		assertThat(book.place(4, 20, Side.BUY, 102, 3)).containsExactly(new Accepted(4, 4),
			new Traded(5, 4, 2, 101, 1), new Traded(6, 4, 3, 101, 1), new Traded(7, 4, 1, 102, 1));
	}

	@Test
	@DisplayName("부분 체결된 주문은 같은 가격에서 순서를 유지한다")
	void partialFillKeepsPriority() {
		book.place(1, 10, Side.BUY, 100, 5);
		book.place(2, 11, Side.BUY, 100, 5);
		book.place(3, 20, Side.SELL, 100, 2);
		assertThat(book.place(4, 21, Side.SELL, 100, 4)).containsExactly(new Accepted(5, 4),
			new Traded(6, 1, 4, 100, 3), new Traded(7, 2, 4, 100, 1));
	}

	@Test
	@DisplayName("같은 계좌의 주문을 만나면 나중 주문의 잔량만 취소한다")
	void preventsSelfTrade() {
		book.place(1, 20, Side.SELL, 100, 2);
		book.place(2, 10, Side.SELL, 101, 2);
		assertThat(book.place(3, 10, Side.BUY, 101, 5)).containsExactly(new Accepted(3, 3),
			new Traded(4, 3, 1, 100, 2), new Canceled(5, 3));
		assertThat(book.snapshot(10).asks()).containsExactly(new BookSnapshot.Level(101, 2));
		assertThat(book.snapshot(10).bids()).isEmpty();
	}

	@Test
	@DisplayName("취소하면 호가창에서 빠지고, 이미 빠진 주문의 취소는 아무 일도 하지 않는다")
	void cancels() {
		book.place(1, 10, Side.BUY, 100, 5);
		assertThat(book.cancel(1)).containsExactly(new Canceled(2, 1));
		assertThat(book.cancel(1)).isEmpty();
		assertThat(book.snapshot(10)).isEqualTo(new BookSnapshot("T", 2, List.of(), List.of()));
	}

	@Test
	@DisplayName("이미 있는 주문이나 잘못된 수량은 받지 않는다")
	void rejectsInvalidInput() {
		book.place(1, 10, Side.BUY, 100, 5);
		assertThatThrownBy(() -> book.place(1, 10, Side.BUY, 100, 5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> book.place(2, 10, Side.BUY, 100, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest(name = "시드 {0}")
	@ValueSource(longs = { 1, 7, 42, 2026, 987654321 })
	@DisplayName("무작위 주문에서 결과가 항상 같고, 호가가 교차하지 않으며, 수량이 보존된다")
	void randomOrdersKeepInvariants(long seed) {
		List<EngineEvent> first = simulate(seed);
		assertThat(simulate(seed)).isEqualTo(first);
	}

	// 무작위 주문과 취소를 넣으면서 매번 조건을 검사하고, 나온 이벤트를 모두 반환한다.
	private static List<EngineEvent> simulate(long seed) {
		Random random = new Random(seed);
		OrderBook book = new OrderBook("T", 0);
		List<EngineEvent> all = new ArrayList<>();
		Map<Long, Long> remaining = new HashMap<>();
		Map<Long, Long> account = new HashMap<>();
		long seq = 0;
		for (long id = 1; id <= 20_000; id++) {
			List<EngineEvent> events;
			if (random.nextInt(4) == 0 && id > 1) {
				events = book.cancel(1 + random.nextLong(id - 1));
			} else {
				long quantity = 1 + random.nextInt(50);
				remaining.put(id, quantity);
				account.put(id, 1L + random.nextInt(5));
				events = book.place(id, account.get(id), random.nextBoolean() ? Side.BUY : Side.SELL,
					90 + random.nextInt(21), quantity);
			}
			for (EngineEvent event : events) {
				assertThat(event.seq()).isEqualTo(++seq);
				switch (event) {
					case Accepted accepted -> assertThat(remaining).containsKey(accepted.orderId());
					case Traded t -> {
						assertThat(account.get(t.buyOrderId())).isNotEqualTo(account.get(t.sellOrderId()));
						assertThat(remaining.merge(t.buyOrderId(), -t.quantity(), Long::sum)).isNotNegative();
						assertThat(remaining.merge(t.sellOrderId(), -t.quantity(), Long::sum)).isNotNegative();
					}
					case Canceled canceled -> assertThat(remaining.remove(canceled.orderId())).isPositive();
				}
			}
			all.addAll(events);
			BookSnapshot snapshot = book.snapshot(Integer.MAX_VALUE);
			if (!snapshot.bids().isEmpty() && !snapshot.asks().isEmpty()) {
				assertThat(snapshot.bids().getFirst().price()).isLessThan(snapshot.asks().getFirst().price());
			}
		}
		BookSnapshot snapshot = book.snapshot(Integer.MAX_VALUE);
		long onBook = snapshot.bids().stream().mapToLong(BookSnapshot.Level::quantity).sum()
			+ snapshot.asks().stream().mapToLong(BookSnapshot.Level::quantity).sum();
		assertThat(onBook).isEqualTo(remaining.values().stream().mapToLong(Long::longValue).sum());
		return all;
	}
}
