package com.exchange.engine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 종목별 호가창을 관리하며 명령을 처리한다.
 *
 * <p>한 스레드만 사용한다. 같은 명령을 같은 순서로 넣으면 항상 같은 결과가 나오므로, 명령 기록을 처음부터 다시
 * 처리하면 호가창과 순번이 그대로 되살아난다.
 */
public final class CommandProcessor {

	// 중복은 발행기가 재시작하며 같은 묶음(최대 200건)을 다시 보낼 때 생기므로 최근 명령만 기억하면 된다.
	// ponytail: 종목별 최근 10,000건까지만 중복을 막는다. 더 오래된 중복이 가능해지면 주문 상태로 확인한다.
	private static final int REMEMBERED_COMMANDS = 10_000;

	private final Map<String, OrderBook> books = new HashMap<>();
	private final Map<String, Map<Long, Boolean>> seen = new HashMap<>();

	/** 명령을 처리한다. 이미 처리한 명령이거나 취소할 주문이 없으면 빈 목록을 반환한다. */
	public List<EngineEvent> process(EngineCommand command) {
		Map<Long, Boolean> commands = seen.computeIfAbsent(command.symbol(), symbol -> recentCommands());
		if (commands.putIfAbsent(command.commandId(), Boolean.TRUE) != null) {
			return List.of();
		}
		OrderBook book = books.computeIfAbsent(command.symbol(), symbol -> new OrderBook(symbol, 0));
		return switch (command.type()) {
			case PLACE -> book.place(command.orderId(), command.accountId(), command.side(), command.price(),
				command.quantity());
			case CANCEL -> book.cancel(command.orderId());
		};
	}

	/** 종목의 호가창. 처리한 명령이 없으면 비어 있다. */
	public BookSnapshot snapshot(String symbol, int depth) {
		OrderBook book = books.get(symbol);
		return book == null ? BookSnapshot.empty(symbol) : book.snapshot(depth);
	}

	// 오래된 명령부터 잊는 목록.
	private static Map<Long, Boolean> recentCommands() {
		return new LinkedHashMap<>() {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
				return size() > REMEMBERED_COMMANDS;
			}
		};
	}
}
