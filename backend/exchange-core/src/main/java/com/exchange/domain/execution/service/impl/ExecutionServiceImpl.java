package com.exchange.domain.execution.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.account.entity.Account;
import com.exchange.domain.account.entity.Position;
import com.exchange.domain.account.repository.AccountRepository;
import com.exchange.domain.account.repository.PositionRepository;
import com.exchange.domain.account.service.AccountAccess;
import com.exchange.domain.execution.dto.ExecutionResponse;
import com.exchange.domain.execution.dto.TradeResponse;
import com.exchange.domain.execution.entity.Execution;
import com.exchange.domain.execution.exception.EngineMismatchException;
import com.exchange.domain.execution.repository.ExecutionRepository;
import com.exchange.domain.execution.service.ExecutionService;
import com.exchange.domain.execution.service.Settled;
import com.exchange.domain.ledger.entity.LedgerRef;
import com.exchange.domain.ledger.entity.LedgerType;
import com.exchange.domain.ledger.entity.RefType;
import com.exchange.domain.ledger.service.LedgerService;
import com.exchange.domain.order.entity.Order;
import com.exchange.domain.order.entity.OrderStatus;
import com.exchange.domain.order.repository.OrderRepository;
import com.exchange.domain.stock.exception.StockException;
import com.exchange.domain.stock.repository.StockRepository;
import com.exchange.engine.EngineEvent;
import com.exchange.engine.EngineResult;
import com.exchange.engine.Side;
import com.exchange.global.error.ErrorCode;
import com.exchange.global.response.CursorPage;

import lombok.RequiredArgsConstructor;

/** 체결 반영. 잠금 순서를 계좌(ID 순) → 보유 종목 → 주문으로 고정해 데드락을 막는다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ExecutionServiceImpl implements ExecutionService {

	private static final int RECENT_TRADES = 100;

	private final ExecutionRepository executionRepository;
	private final OrderRepository orderRepository;
	private final AccountRepository accountRepository;
	private final PositionRepository positionRepository;
	private final StockRepository stockRepository;
	private final LedgerService ledgerService;
	private final AccountAccess accountAccess;
	private final ApplicationEventPublisher eventPublisher;

	@Override
	@Transactional
	public void settle(String symbol, List<EngineResult> results) {
		long applied = stockRepository.findLastEventSeq(symbol)
			.orElseThrow(() -> new StockException(ErrorCode.STOCK_NOT_FOUND));
		List<EngineEvent> events = new ArrayList<>();
		long expected = applied;
		for (EngineResult result : results) {
			if (result.events().getFirst().seq() <= applied) {
				verifyApplied(symbol, result);
				continue;
			}
			for (EngineEvent event : result.toEngineEvents()) {
				// 순번이 비면 결과를 잃은 것이다. 건너뛰면 잔고가 틀어진다.
				if (event.seq() != ++expected) {
					throw new EngineMismatchException("이벤트 순번 누락: symbol=" + symbol + ", seq=" + event.seq());
				}
				events.add(event);
			}
		}
		if (events.isEmpty()) {
			return;
		}
		Locked locked = lock(symbol, events);
		events.forEach(event -> apply(symbol, event, locked));
		// 같은 순번을 다른 스레드가 먼저 반영했다면 전체를 되돌린다.
		if (stockRepository.advanceEventSeq(symbol, applied, expected) != 1) {
			throw new IllegalStateException("이벤트가 동시에 반영됨: symbol=" + symbol);
		}
		eventPublisher.publishEvent(new Settled(symbol,
			locked.accounts().values().stream().map(Account::getUserId).collect(Collectors.toSet())));
	}

	@Override
	public List<TradeResponse> recentTrades(String symbol) {
		return executionRepository.findBySymbolOrderByEventSeqDesc(symbol, Limit.of(RECENT_TRADES)).stream()
			.map(TradeResponse::from).toList();
	}

	@Override
	public CursorPage<ExecutionResponse> findMine(long userId, long accountId, Long cursor, int size) {
		accountAccess.requireOwned(userId, accountId);
		List<ExecutionResponse> rows = executionRepository
			.findByAccount(accountId, cursor == null ? Long.MAX_VALUE : cursor, size + 1)
			.stream().map(ExecutionResponse::from).toList();
		return CursorPage.of(rows, size, ExecutionResponse::executionId);
	}

	// 중복이라면 같은 주문이 같은 순번으로 반영돼 있어야 한다. 아니라면 Kafka 기록이 바뀐 것이다.
	private void verifyApplied(String symbol, EngineResult result) {
		EngineEvent first = result.toEngineEvents().getFirst();
		boolean same = switch (first) {
			case EngineEvent.Accepted e -> orderRepository.existsByOrderIdAndEngineSeq(e.orderId(), e.seq());
			case EngineEvent.Canceled e -> orderRepository.existsByOrderIdAndStatus(e.orderId(), OrderStatus.CANCELED);
			case EngineEvent.Traded e -> false;
		};
		if (!same) {
			throw new EngineMismatchException("반영한 순번과 다른 결과: symbol=" + symbol + ", seq=" + first.seq());
		}
	}

	// 관련된 행을 정해진 순서로 잠그고 읽는다.
	private Locked lock(String symbol, List<EngineEvent> events) {
		Set<Long> orderIds = events.stream().flatMap(ExecutionServiceImpl::orderIds).collect(Collectors.toSet());
		// 처음 사는 종목의 행을 만드는 INSERT는 행을 새로 만들 뿐이라 잠금 순서에 영향을 주지 않는다.
		List<Long> accountIds = orderRepository.findAccountIds(orderIds);
		Map<Long, Account> accounts = accountRepository.lockAll(accountIds).stream()
			.collect(Collectors.toMap(Account::getAccountId, Function.identity()));
		accountIds.stream().sorted().forEach(id -> positionRepository.insertIfAbsent(id, symbol));
		Map<Long, Position> positions = positionRepository.lockAll(symbol, accountIds).stream()
			.collect(Collectors.toMap(p -> p.getId().accountId(), Function.identity()));
		Map<Long, Order> orders = orderRepository.lockAll(orderIds).stream()
			.collect(Collectors.toMap(Order::getOrderId, Function.identity()));
		return new Locked(accounts, positions, orders);
	}

	// 이벤트 종류에 맞게 주문, 잔고, 보유 종목, 원장을 바꾼다.
	private void apply(String symbol, EngineEvent event, Locked locked) {
		switch (event) {
			case EngineEvent.Accepted accepted -> locked.order(accepted.orderId()).accept(accepted.seq());
			case EngineEvent.Traded traded -> trade(symbol, traded, locked);
			case EngineEvent.Canceled canceled -> cancel(symbol, locked.order(canceled.orderId()), locked);
		}
	}

	// 체결 한 건을 복식부기로 반영한다.
	private void trade(String symbol, EngineEvent.Traded event, Locked locked) {
		Order buy = locked.order(event.buyOrderId());
		Order sell = locked.order(event.sellOrderId());
		long quantity = event.quantity();
		long amount = Math.multiplyExact(event.price(), quantity);
		Execution execution = executionRepository.save(Execution.of(symbol, event));

		// 지정가보다 싸게 샀으면 차액만큼 예약금이 더 풀려 주문 가능 금액으로 돌아온다.
		long released = buy.fill(quantity);
		sell.fill(quantity);
		locked.account(buy).payForBuy(amount, released);
		locked.account(sell).receive(amount);
		locked.position(buy).buy(quantity, amount);
		locked.position(sell).sell(quantity, amount);

		LedgerRef ref = ledgerService.open(LedgerType.BUY, RefType.EXECUTION, execution.getExecutionId());
		ledgerService.cash(ref, buy.getAccountId(), -amount, -released);
		ledgerService.cash(ref.as(LedgerType.SELL), sell.getAccountId(), amount, 0);
		ledgerService.stock(ref, buy.getAccountId(), symbol, quantity, 0);
		ledgerService.stock(ref.as(LedgerType.SELL), sell.getAccountId(), symbol, -quantity, -quantity);
	}

	// 남은 수량의 예약을 풀고 취소한다.
	private void cancel(String symbol, Order order, Locked locked) {
		long remaining = order.remaining();
		long released = order.cancel();
		LedgerRef ref = ledgerService.open(LedgerType.RELEASE, RefType.ORDER, order.getOrderId());
		if (order.getSide() == Side.BUY) {
			locked.account(order).release(released);
			ledgerService.cash(ref, order.getAccountId(), 0, -released);
			return;
		}
		locked.position(order).release(remaining);
		ledgerService.stock(ref, order.getAccountId(), symbol, 0, -remaining);
	}

	// 이벤트와 관련된 주문 ID.
	private static Stream<Long> orderIds(EngineEvent event) {
		return switch (event) {
			case EngineEvent.Accepted e -> Stream.of(e.orderId());
			case EngineEvent.Traded e -> Stream.of(e.buyOrderId(), e.sellOrderId());
			case EngineEvent.Canceled e -> Stream.of(e.orderId());
		};
	}

	/** 한 트랜잭션에서 잠근 행들. */
	private record Locked(Map<Long, Account> accounts, Map<Long, Position> positions, Map<Long, Order> orders) {

		// 잠근 주문을 찾는다.
		Order order(long orderId) {
			return orders.get(orderId);
		}

		// 주문의 계좌를 찾는다.
		Account account(Order order) {
			return accounts.get(order.getAccountId());
		}

		// 주문의 보유 종목을 찾는다.
		Position position(Order order) {
			return positions.get(order.getAccountId());
		}
	}
}
