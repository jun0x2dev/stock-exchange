package com.exchange.domain.bot.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.exchange.domain.account.repository.AccountRepository;
import com.exchange.domain.audit.service.AuditService;
import com.exchange.domain.bot.service.QuoteBotService;
import com.exchange.domain.order.dto.PlaceOrderRequest;
import com.exchange.domain.order.entity.Order;
import com.exchange.domain.order.entity.OrderType;
import com.exchange.domain.order.repository.OrderRepository;
import com.exchange.domain.order.service.OrderService;
import com.exchange.domain.outbox.service.OutboxService;
import com.exchange.domain.user.repository.UserRepository;
import com.exchange.engine.BookSnapshot;
import com.exchange.engine.EngineCommand;
import com.exchange.engine.Side;
import com.exchange.market.PricePolicy;

import lombok.RequiredArgsConstructor;

/** 호가 봇 구현. 취소와 새 주문을 나눠 커밋해, 새 주문이 거부돼도 옛 호가는 반드시 거둔다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class QuoteBotServiceImpl implements QuoteBotService {

	/** 호가를 내는 체험용 사용자. */
	static final String MAKER = "maker";

	/** 거래를 일으키는 체험용 사용자. */
	static final String TAKER = "taker";

	/** 한쪽에 내는 호가 수. 많을수록 시세가 움직일 때 주문과 원장이 늘어난다. */
	static final int LEVELS = 3;

	private final UserRepository userRepository;
	private final AccountRepository accountRepository;
	private final OrderRepository orderRepository;
	private final OrderService orderService;
	private final OutboxService outboxService;
	private final AuditService auditService;
	private final TransactionTemplate transaction;

	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void quote(String symbol, long price) {
		find(MAKER).ifPresent(maker -> {
			List<Level> wanted = levels(price);
			Set<Level> kept = new HashSet<>();
			List<Order> stale = new ArrayList<>();
			for (Order order : orderRepository.findCancelable(maker.accountId(), symbol)) {
				Level level = new Level(order.getSide(), order.getPrice());
				// 같은 가격에 두 개가 있거나 절반 넘게 체결됐으면 새로 낸다.
				if (wanted.contains(level) && order.remaining() * 2 >= quantity(level) && kept.add(level)) {
					continue;
				}
				stale.add(order);
			}
			// 취소 명령이 먼저 커밋되므로 엔진도 먼저 처리한다. 그래서 봇끼리 체결되지 않는다.
			transaction.executeWithoutResult(status -> cancel(stale));
			// 매수는 계좌, 매도는 보유 종목 행을 잠근다. 체결 반영과 같은 순서(계좌 → 보유 종목)로 잠그도록 매수를 먼저 낸다.
			transaction.executeWithoutResult(status -> wanted.stream().filter(level -> !kept.contains(level))
				.sorted(Comparator.comparing(level -> level.side() == Side.SELL))
				.forEach(level -> place(maker, symbol, level.side(), level.price(), quantity(level))));
		});
	}

	@Override
	@Transactional
	public void withdraw(String symbol) {
		find(MAKER).ifPresent(maker -> cancel(orderRepository.findCancelable(maker.accountId(), symbol)));
	}

	@Override
	@Transactional
	public void trade(String symbol, boolean buy, long quantity) {
		BookSnapshot book = orderService.book(symbol);
		List<BookSnapshot.Level> opposite = buy ? book.asks() : book.bids();
		if (opposite.isEmpty()) {
			return;
		}
		find(TAKER).ifPresent(taker -> place(taker, symbol, buy ? Side.BUY : Side.SELL, opposite.getFirst().price(),
			Math.min(quantity, opposite.getFirst().quantity())));
	}

	// 시세 양쪽으로 한 호가씩 떨어진 가격들.
	private static List<Level> levels(long price) {
		List<Level> levels = new ArrayList<>();
		long bid = price;
		long ask = price;
		for (int i = 0; i < LEVELS; i++) {
			bid = PricePolicy.below(bid);
			ask = PricePolicy.above(ask);
			levels.add(new Level(Side.BUY, bid));
			levels.add(new Level(Side.SELL, ask));
		}
		return levels;
	}

	// 같은 가격에는 늘 같은 수량을 내서, 다시 계산해도 바뀐 것으로 보이지 않게 한다.
	private static long quantity(Level level) {
		return 30 + Math.floorMod(Long.hashCode(level.price()) * 31 + level.side().ordinal(), 70);
	}

	// 아직 취소하지 않은 주문에만 취소를 요청한다. 그사이 체결된 주문은 건너뛴다.
	private void cancel(List<Order> orders) {
		for (Order order : orders) {
			if (orderRepository.requestCancel(order.getOrderId()) == 1) {
				outboxService.append(EngineCommand.Type.CANCEL, order.getSymbol(), order.getOrderId());
				auditService.recordSuccess("CANCEL_ORDER", "order=" + order.getOrderId());
			}
		}
	}

	// 일반 주문과 같은 검증과 예약을 거친다.
	private void place(Trader trader, String symbol, Side side, long price, long quantity) {
		orderService.place(trader.userId(), "bot-" + UUID.randomUUID(),
			new PlaceOrderRequest(trader.accountId(), symbol, side, OrderType.LIMIT, price, quantity));
	}

	// 봇이 쓸 체험용 계좌. 체험용 데이터를 만들지 않았으면 없다.
	private Optional<Trader> find(String loginId) {
		return userRepository.findByLoginId(loginId).flatMap(user -> accountRepository
			.findByUserIdOrderByAccountId(user.getUserId()).stream().findFirst()
			.map(account -> new Trader(user.getUserId(), account.getAccountId())));
	}

	/** 봇의 사용자와 계좌. */
	private record Trader(long userId, long accountId) {
	}

	/** 호가 한 줄. */
	private record Level(Side side, long price) {
	}
}
