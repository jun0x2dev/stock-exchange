package com.exchange.domain.matching.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.exchange.domain.execution.service.ExecutionService;
import com.exchange.domain.matching.service.MatchingService;
import com.exchange.domain.order.dto.OrderCommandReady;
import com.exchange.domain.order.repository.OrderRepository;
import com.exchange.domain.order.repository.OrderRepository.EngineOrder;
import com.exchange.domain.stock.entity.Stock;
import com.exchange.domain.stock.entity.StockStatus;
import com.exchange.domain.stock.repository.StockRepository;
import com.exchange.domain.stock.service.StockService;
import com.exchange.engine.BookSnapshot;
import com.exchange.engine.EngineEvent;
import com.exchange.engine.OrderBook;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 매칭 워커. 같은 종목은 항상 같은 스레드가 처리한다.
 *
 * <p>메모리 큐에 명령을 쌓지 않고, 커밋된 주문을 DB에서 순서대로 읽는다. 그래서 전달 직전에 죽어도 주문을
 * 잃지 않고, 재시작과 장애 복구가 평소와 같은 경로를 탄다.
 */
// 매칭과 조회 사이에 트랜잭션을 열어 두지 않는다. DB 변경은 ExecutionService의 트랜잭션에서만 한다.
@Slf4j
@Service
public class MatchingServiceImpl implements MatchingService {

	private static final int BATCH = 200;
	private static final int DEPTH = 10;
	private static final int MAX_FAILURES = 3;

	private final OrderRepository orderRepository;
	private final StockRepository stockRepository;
	private final StockService stockService;
	private final ExecutionService executionService;
	private final Map<String, SymbolState> states = new ConcurrentHashMap<>();
	private final List<ScheduledExecutorService> workers;

	/** 워커 스레드를 정해진 수만큼 만든다. */
	public MatchingServiceImpl(OrderRepository orderRepository, StockRepository stockRepository,
			StockService stockService, ExecutionService executionService,
			@Value("${exchange.matching.workers}") int workerCount) {
		this.orderRepository = orderRepository;
		this.stockRepository = stockRepository;
		this.stockService = stockService;
		this.executionService = executionService;
		this.workers = IntStream.range(0, workerCount)
			.mapToObj(i -> Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "matching-" + i)))
			.toList();
	}

	/** 기동하면 모든 종목의 호가창을 복원하고, 전달되지 못한 주문과 취소를 이어서 처리한다. */
	@EventListener(ApplicationReadyEvent.class)
	void restoreAll() {
		stockRepository.findAllSymbols().forEach(this::signal);
	}

	/** 주문이나 취소가 커밋되면 엔진을 깨운다. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onCommitted(OrderCommandReady event) {
		signal(event.symbol());
	}

	@Override
	public void signal(String symbol) {
		SymbolState state = states.computeIfAbsent(symbol, SymbolState::new);
		// 종목마다 대기 작업이 하나뿐이라 워커 큐는 종목 수를 넘지 않는다.
		if (state.scheduled.compareAndSet(false, true)) {
			worker(symbol).execute(() -> run(state));
		}
	}

	@Override
	public void reload(String symbol) {
		SymbolState state = states.computeIfAbsent(symbol, SymbolState::new);
		worker(symbol).execute(() -> {
			state.reset();
			run(state);
		});
	}

	@Override
	public BookSnapshot snapshot(String symbol) {
		SymbolState state = states.get(symbol);
		if (state != null) {
			return state.snapshot;
		}
		return BookSnapshot.empty(stockService.get(symbol).getSymbol());
	}

	/** 처리 중인 명령을 마치고 워커를 멈춘다. */
	@PreDestroy
	void shutdown() throws InterruptedException {
		workers.forEach(ScheduledExecutorService::shutdown);
		for (ScheduledExecutorService worker : workers) {
			worker.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	// 종목을 담당하는 워커를 고른다.
	private ScheduledExecutorService worker(String symbol) {
		return workers.get(Math.floorMod(symbol.hashCode(), workers.size()));
	}

	// 밀린 명령을 처리하고, 실패하면 호가창을 버린 뒤 다시 시도하거나 종목을 멈춘다.
	private void run(SymbolState state) {
		// 처리 도중 들어온 신호를 놓치지 않도록 읽기 전에 내린다.
		state.scheduled.set(false);
		if (state.halted) {
			return;
		}
		try {
			boolean more = drain(state);
			state.failures = 0;
			if (more) {
				signal(state.symbol);
			}
		} catch (RuntimeException e) {
			recover(state, e);
		}
	}

	// 명령을 한 묶음 처리하고, 더 남았을 수 있으면 true를 반환한다. 다른 종목이 밀리지 않도록 나눠서 처리한다.
	private boolean drain(SymbolState state) {
		if (state.book == null) {
			load(state);
			if (state.halted) {
				return false;
			}
		}
		List<Command> commands = pending(state.symbol);
		boolean progressed = commands.isEmpty();
		for (Command command : commands) {
			List<EngineEvent> events = command.apply(state.book);
			if (events.isEmpty()) {
				continue;
			}
			executionService.settle(state.symbol, events);
			state.publish(DEPTH);
			progressed = true;
		}
		if (!progressed) {
			// DB에는 남아 있는데 호가창에 없다. 그대로 두면 같은 명령을 끝없이 읽는다.
			throw new IllegalStateException("호가창과 DB가 어긋남: symbol=" + state.symbol);
		}
		return !commands.isEmpty();
	}

	// 접수 대기 주문과 취소 요청을 발생 시각 순으로 합친다.
	private List<Command> pending(String symbol) {
		List<Command> commands = new ArrayList<>();
		for (EngineOrder order : orderRepository.findAccepted(symbol, Limit.of(BATCH))) {
			commands.add(new Command(order.createdAt(), order.orderId(), order));
			if (order.cancelRequestedAt() != null) {
				commands.add(new Command(order.cancelRequestedAt(), order.orderId(), null));
			}
		}
		orderRepository.findCancelRequested(symbol, Limit.of(BATCH))
			.forEach(cancel -> commands.add(new Command(cancel.requestedAt(), cancel.orderId(), null)));
		commands.sort(Comparator.comparing(Command::at).thenComparing(Command::orderId));
		return commands;
	}

	// DB에서 호가창을 복원한다. 멈춘 종목은 조회만 할 수 있게 복원하고 명령은 처리하지 않는다.
	private void load(SymbolState state) {
		Stock stock = stockService.get(state.symbol);
		OrderBook book = new OrderBook(state.symbol, stock.getLastEventSeq());
		orderRepository.findOnBook(state.symbol)
			.forEach(o -> book.restore(o.orderId(), o.accountId(), o.side(), o.price(), o.remaining()));
		state.book = book;
		state.halted = stock.getStatus() == StockStatus.HALTED;
		state.publish(DEPTH);
	}

	// 매칭은 됐지만 저장하지 못했을 수 있으므로 호가창을 믿지 않고 DB 기준으로 되돌린다.
	private void recover(SymbolState state, RuntimeException cause) {
		if (Thread.currentThread().isInterrupted()) {
			return;
		}
		state.book = null;
		state.failures++;
		log.error("매칭 실패: symbol={}, failures={}", state.symbol, state.failures, cause);
		if (state.failures < MAX_FAILURES) {
			worker(state.symbol).schedule(() -> run(state), state.failures, TimeUnit.SECONDS);
			return;
		}
		// 실패한 명령을 건너뛰면 잔고가 틀어질 수 있으므로 종목을 멈춘다.
		state.halted = true;
		try {
			stockService.halt(state.symbol);
		} catch (RuntimeException e) {
			log.error("거래 정지를 저장하지 못함: symbol={}", state.symbol, e);
		}
		log.error("종목 거래 정지: symbol={}", state.symbol);
	}

	/** 엔진에 넣을 명령. 주문이 없으면 취소다. */
	private record Command(Instant at, long orderId, EngineOrder order) {

		// 호가창에 적용한다.
		List<EngineEvent> apply(OrderBook book) {
			return order == null ? book.cancel(orderId)
				: book.place(orderId, order.accountId(), order.side(), order.price(), order.remaining());
		}
	}

	/** 종목 하나의 상태. snapshot과 scheduled 외에는 담당 워커만 쓴다. */
	private static final class SymbolState {

		private final String symbol;
		private final AtomicBoolean scheduled = new AtomicBoolean();
		private volatile BookSnapshot snapshot;
		private OrderBook book;
		private int failures;
		private boolean halted;

		// 아직 복원하지 않은 상태로 만든다.
		private SymbolState(String symbol) {
			this.symbol = symbol;
			this.snapshot = BookSnapshot.empty(symbol);
		}

		// 조회용 호가창을 바꾼다.
		private void publish(int depth) {
			snapshot = book.snapshot(depth);
		}

		// 다음 처리에서 DB부터 다시 읽게 한다.
		private void reset() {
			book = null;
			failures = 0;
			halted = false;
		}
	}
}
