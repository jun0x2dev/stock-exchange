package com.exchange.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 한 종목의 호가창. 가격·시간 우선으로 매칭한다.
 *
 * <p>담당 워커 스레드만 사용한다. 시계와 난수를 쓰지 않아 같은 입력에는 항상 같은 결과가 나온다.
 */
public final class OrderBook {

	private final String symbol;
	private final TreeMap<Long, LinkedHashMap<Long, BookOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
	private final TreeMap<Long, LinkedHashMap<Long, BookOrder>> asks = new TreeMap<>();
	private final Map<Long, BookOrder> orders = new HashMap<>();
	private long lastSeq;

	/** 마지막으로 반영된 이벤트 순번에서 이어 가는 빈 호가창을 만든다. */
	public OrderBook(String symbol, long lastSeq) {
		this.symbol = symbol;
		this.lastSeq = lastSeq;
	}

	/** 저장된 미체결 주문을 되돌린다. 접수 순번 순으로 호출해야 시간 우선이 유지된다. */
	public void restore(long orderId, long accountId, Side side, long price, long remaining) {
		rest(new BookOrder(orderId, accountId, side, price, remaining));
	}

	/** 지정가 주문을 매칭하고 남은 수량을 호가창에 올린다. */
	public List<EngineEvent> place(long orderId, long accountId, Side side, long price, long quantity) {
		if (price <= 0 || quantity <= 0 || orders.containsKey(orderId)) {
			throw new IllegalArgumentException("엔진에 넣을 수 없는 주문: " + orderId);
		}
		List<EngineEvent> events = new ArrayList<>();
		events.add(new EngineEvent.Accepted(++lastSeq, orderId));
		BookOrder taker = new BookOrder(orderId, accountId, side, price, quantity);
		TreeMap<Long, LinkedHashMap<Long, BookOrder>> opposite = side == Side.BUY ? asks : bids;

		while (taker.remaining > 0 && !opposite.isEmpty() && crosses(taker, opposite.firstKey())) {
			BookOrder maker = opposite.firstEntry().getValue().firstEntry().getValue();
			if (maker.accountId == accountId) {
				// 자전거래 방지: 나중에 들어온 주문의 잔량을 취소한다.
				events.add(new EngineEvent.Canceled(++lastSeq, orderId));
				return events;
			}
			long traded = Math.min(taker.remaining, maker.remaining);
			BookOrder buy = side == Side.BUY ? taker : maker;
			BookOrder sell = side == Side.BUY ? maker : taker;
			events.add(new EngineEvent.Traded(++lastSeq, buy.orderId, sell.orderId, maker.price, traded));
			taker.remaining -= traded;
			maker.remaining -= traded;
			if (maker.remaining == 0) {
				remove(maker);
			}
		}
		if (taker.remaining > 0) {
			rest(taker);
		}
		return events;
	}

	/** 호가창에 남은 주문을 취소한다. 이미 빠진 주문이면 빈 목록을 반환한다. */
	public List<EngineEvent> cancel(long orderId) {
		BookOrder order = orders.get(orderId);
		if (order == null) {
			return List.of();
		}
		remove(order);
		return List.of(new EngineEvent.Canceled(++lastSeq, orderId));
	}

	/** 가격별 잔량을 지정한 단계까지 담은 불변 스냅샷을 만든다. */
	public BookSnapshot snapshot(int depth) {
		return new BookSnapshot(symbol, lastSeq, levels(bids, depth), levels(asks, depth));
	}

	// 상대 최우선 호가와 가격이 맞는지 확인한다.
	private static boolean crosses(BookOrder taker, long bestPrice) {
		return taker.side == Side.BUY ? taker.price >= bestPrice : taker.price <= bestPrice;
	}

	// 주문을 같은 가격의 마지막 순서로 올린다.
	private void rest(BookOrder order) {
		sideOf(order).computeIfAbsent(order.price, price -> new LinkedHashMap<>()).put(order.orderId, order);
		orders.put(order.orderId, order);
	}

	// 주문을 호가창에서 빼고, 비게 된 가격을 정리한다.
	private void remove(BookOrder order) {
		TreeMap<Long, LinkedHashMap<Long, BookOrder>> side = sideOf(order);
		LinkedHashMap<Long, BookOrder> level = side.get(order.price);
		level.remove(order.orderId);
		if (level.isEmpty()) {
			side.remove(order.price);
		}
		orders.remove(order.orderId);
	}

	// 주문이 놓이는 쪽의 호가를 고른다.
	private TreeMap<Long, LinkedHashMap<Long, BookOrder>> sideOf(BookOrder order) {
		return order.side == Side.BUY ? bids : asks;
	}

	// 가격별 잔량을 합산한다.
	private static List<BookSnapshot.Level> levels(TreeMap<Long, LinkedHashMap<Long, BookOrder>> side, int depth) {
		return side.entrySet().stream()
			.limit(depth)
			.map(e -> new BookSnapshot.Level(e.getKey(),
				e.getValue().values().stream().mapToLong(o -> o.remaining).reduce(0, Math::addExact)))
			.toList();
	}

	/** 호가창에 놓인 주문. */
	private static final class BookOrder {

		private final long orderId;
		private final long accountId;
		private final Side side;
		private final long price;
		private long remaining;

		// 남은 수량을 가진 주문을 만든다.
		private BookOrder(long orderId, long accountId, Side side, long price, long remaining) {
			this.orderId = orderId;
			this.accountId = accountId;
			this.side = side;
			this.price = price;
			this.remaining = remaining;
		}
	}
}
