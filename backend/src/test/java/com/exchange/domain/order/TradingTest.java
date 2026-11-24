package com.exchange.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.exchange.IntegrationTest;
import com.exchange.domain.account.dto.AccountResponse;
import com.exchange.domain.account.dto.DepositRequest;
import com.exchange.domain.account.dto.PositionResponse;
import com.exchange.domain.order.dto.OrderResponse;
import com.exchange.domain.order.entity.OrderStatus;
import com.exchange.engine.BookSnapshot;
import com.exchange.engine.Side;
import com.exchange.global.error.BusinessException;
import com.exchange.global.error.ErrorCode;

/** 주문 접수부터 체결·원장까지의 정합성, 동시성, 복구를 검증한다. */
class TradingTest extends IntegrationTest {

	@AfterEach
	void invariantsHold() {
		assertInvariants();
	}

	@Test
	@DisplayName("지정가보다 싸게 체결되면 차액이 주문 가능 금액으로 돌아온다 (DESIGN.md 4장의 예)")
	void buyerGetsPriceImprovement() {
		String symbol = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 10);
		Trader buyer = trader(1_000_000);
		place(seller, symbol, Side.SELL, 70_500, 10);
		OrderResponse buy = place(buyer, symbol, Side.BUY, 71_000, 10);
		awaitProcessed(symbol);

		assertThat(orderService.get(buyer.userId(), buy.orderId()).status()).isEqualTo(OrderStatus.FILLED);
		assertThat(account(buyer)).isEqualTo(new AccountResponse(buyer.accountId(), 295_000, 0, 295_000));
		assertThat(account(seller).cashBalance()).isEqualTo(705_000);
		PositionResponse position = accountService.findPositions(buyer.userId(), buyer.accountId()).getFirst();
		assertThat(position.quantity()).isEqualTo(10);
		assertThat(position.totalCost()).isEqualTo(705_000);
		// 판 사람의 원가는 입고 시점의 기준가다.
		PositionResponse sold = accountService.findPositions(seller.userId(), seller.accountId()).getFirst();
		assertThat(sold.realizedPnl()).isEqualTo(705_000 - 710_000);
		assertThat(jdbc.queryForList("SELECT type || ' ' || cash_delta || ' ' || reserved_delta FROM cash_ledger "
			+ "WHERE account_id = ? ORDER BY ledger_id", String.class, buyer.accountId()))
			.containsExactly("DEPOSIT 1000000 0", "RESERVE 0 710000", "BUY -705000 -710000");
	}

	@Test
	@DisplayName("부분 체결 뒤 취소하면 남은 예약만 풀린다")
	void cancelReleasesRemainingReserve() {
		String symbol = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 3);
		Trader buyer = trader(1_000_000);
		OrderResponse buy = place(buyer, symbol, Side.BUY, 71_000, 10);
		place(seller, symbol, Side.SELL, 71_000, 3);
		awaitProcessed(symbol);
		assertThat(account(buyer).reservedCash()).isEqualTo(71_000 * 7);

		orderService.cancel(buyer.userId(), buy.orderId());
		awaitProcessed(symbol);
		OrderResponse canceled = orderService.get(buyer.userId(), buy.orderId());
		assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
		assertThat(canceled.filledQuantity()).isEqualTo(3);
		assertThat(account(buyer).availableCash()).isEqualTo(1_000_000 - 71_000 * 3);
		assertError(ErrorCode.ORDER_NOT_CANCELABLE, () -> orderService.cancel(buyer.userId(), buy.orderId()));
	}

	@Test
	@DisplayName("자전거래로 취소된 주문의 예약이 풀린다")
	void selfTradeIsCanceled() {
		String symbol = stock();
		Trader trader = trader(1_000_000);
		giveStock(trader, symbol, 5);
		place(trader, symbol, Side.SELL, 71_000, 5);
		OrderResponse buy = place(trader, symbol, Side.BUY, 71_000, 5);
		awaitProcessed(symbol);
		assertThat(orderService.get(trader.userId(), buy.orderId()).status()).isEqualTo(OrderStatus.CANCELED);
		assertThat(account(trader).availableCash()).isEqualTo(1_000_000);
	}

	@Test
	@DisplayName("잔고, 수량, 호가 단위, 상·하한가를 어긴 주문은 저장하지 않는다")
	void rejectsInvalidOrders() {
		String symbol = stock();
		Trader trader = trader(100_000);
		assertError(ErrorCode.INSUFFICIENT_CASH, () -> place(trader, symbol, Side.BUY, 71_000, 2));
		assertError(ErrorCode.INSUFFICIENT_QUANTITY, () -> place(trader, symbol, Side.SELL, 71_000, 1));
		assertError(ErrorCode.INVALID_TICK_SIZE, () -> place(trader, symbol, Side.BUY, 71_050, 1));
		assertError(ErrorCode.PRICE_OUT_OF_RANGE, () -> place(trader, symbol, Side.BUY, 92_400, 1));
		assertError(ErrorCode.PRICE_OUT_OF_RANGE, () -> place(trader, symbol, Side.BUY, 49_600, 1));
		assertError(ErrorCode.STOCK_NOT_FOUND, () -> place(trader, "NONE", Side.BUY, 71_000, 1));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE account_id = ?", Integer.class,
			trader.accountId())).isZero();
	}

	@Test
	@DisplayName("같은 멱등키로 다시 주문하면 기존 주문을, 다른 내용이면 거부를 반환한다")
	void idempotentOrder() {
		String symbol = stock();
		Trader trader = trader(1_000_000);
		String key = key();
		OrderResponse first = orderService.place(trader.userId(), key, request(trader, symbol, Side.BUY, 71_000, 1));
		OrderResponse again = orderService.place(trader.userId(), key, request(trader, symbol, Side.BUY, 71_000, 1));
		assertThat(again.orderId()).isEqualTo(first.orderId());
		assertError(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
			() -> orderService.place(trader.userId(), key, request(trader, symbol, Side.BUY, 71_000, 2)));
		assertThat(account(trader).reservedCash()).isEqualTo(71_000);
	}

	@Test
	@DisplayName("같은 멱등키의 동시 주문과 동시 입금은 한 번만 반영된다")
	void concurrentSameKey() throws Exception {
		String symbol = stock();
		Trader trader = trader(1_000_000);
		String key = key();
		assertThat(runConcurrently(Collections.nCopies(20,
			() -> orderService.place(trader.userId(), key, request(trader, symbol, Side.BUY, 71_000, 1))))).isEqualTo(20);
		assertThat(runConcurrently(Collections.nCopies(20,
			() -> accountService.deposit(trader.userId(), trader.accountId(), key, new DepositRequest(5_000L)))))
			.isEqualTo(20);
		assertThat(account(trader)).isEqualTo(new AccountResponse(trader.accountId(), 1_005_000, 71_000, 934_000));
	}

	@Test
	@DisplayName("한 계좌가 여러 종목을 동시에 주문해도 예수금을 넘지 않는다")
	void concurrentOrdersOnManySymbols() throws Exception {
		List<String> symbols = Stream.generate(this::stock).limit(5).toList();
		Trader trader = trader(710_000);
		List<Runnable> tasks = IntStream.range(0, 40).<Runnable>mapToObj(i ->
			() -> place(trader, symbols.get(i % symbols.size()), Side.BUY, 71_000, 1)).toList();
		assertThat(runConcurrently(tasks)).isEqualTo(10);
		assertThat(account(trader).availableCash()).isZero();
		symbols.forEach(this::awaitProcessed);
	}

	@Test
	@DisplayName("보유 수량보다 많은 동시 매도는 막힌다")
	void concurrentSells() throws Exception {
		String symbol = stock();
		Trader trader = trader(0);
		giveStock(trader, symbol, 10);
		assertThat(runConcurrently(Collections.nCopies(30, () -> place(trader, symbol, Side.SELL, 71_000, 1))))
			.isEqualTo(10);
		awaitProcessed(symbol);
	}

	@Test
	@DisplayName("취소와 체결이 겹쳐도 둘 중 하나로만 끝난다")
	void cancelRacesWithFill() throws Exception {
		String symbol = stock();
		Trader buyer = trader(10_000_000);
		Trader seller = trader(0);
		giveStock(seller, symbol, 30);
		for (int i = 0; i < 30; i++) {
			OrderResponse buy = place(buyer, symbol, Side.BUY, 71_000, 1);
			runConcurrently(List.of(
				() -> orderService.cancel(buyer.userId(), buy.orderId()),
				() -> place(seller, symbol, Side.SELL, 71_000, 1)));
			awaitProcessed(symbol);
			assertThat(orderService.get(buyer.userId(), buy.orderId()).status())
				.isIn(OrderStatus.FILLED, OrderStatus.CANCELED);
			assertInvariants();
		}
	}

	@Test
	@DisplayName("여러 계좌와 종목의 무작위 주문·취소 뒤에도 불변식이 유지되고, 다시 만든 호가창이 같다")
	void randomTrading() throws Exception {
		long seed = System.nanoTime();
		System.out.println("무작위 주문 시드: " + seed);
		List<String> symbols = Stream.generate(this::stock).limit(3).toList();
		List<Trader> traders = Stream.generate(() -> trader(20_000_000)).limit(6).toList();
		traders.forEach(t -> symbols.forEach(s -> giveStock(t, s, 300)));
		List<Runnable> tasks = new ArrayList<>();
		for (int worker = 0; worker < 8; worker++) {
			Random random = new Random(seed + worker);
			tasks.add(() -> {
				List<OrderResponse> placed = new ArrayList<>();
				for (int i = 0; i < 120; i++) {
					Trader trader = traders.get(random.nextInt(traders.size()));
					try {
						if (random.nextInt(4) == 0 && !placed.isEmpty()) {
							OrderResponse target = placed.get(random.nextInt(placed.size()));
							orderService.cancel(owner(traders, target), target.orderId());
						} else {
							placed.add(place(trader, symbols.get(random.nextInt(symbols.size())),
								random.nextBoolean() ? Side.BUY : Side.SELL, 70_500 + 100L * random.nextInt(11),
								1 + random.nextInt(20)));
						}
					} catch (BusinessException expected) {
						// 잔고 부족이나 이미 끝난 주문의 취소는 정상적인 거부다.
					}
				}
			});
		}
		assertThat(runConcurrently(tasks)).isEqualTo(8);
		symbols.forEach(this::awaitProcessed);
		for (String symbol : symbols) {
			BookSnapshot before = matchingService.snapshot(symbol);
			matchingService.reload(symbol);
			place(traders.getFirst(), symbol, Side.BUY, 50_000, 1);
			awaitProcessed(symbol);
			BookSnapshot after = matchingService.snapshot(symbol);
			assertThat(after.asks()).isEqualTo(before.asks());
			assertThat(after.bids().getFirst()).isEqualTo(
				before.bids().isEmpty() ? new BookSnapshot.Level(50_000, 1) : before.bids().getFirst());
		}
	}

	@Test
	@DisplayName("엔진이 멈춘 동안 커밋된 주문과 취소는 재시작하면 순서대로 처리된다")
	void restartProcessesCommittedOrders() {
		String symbol = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 10);
		Trader first = trader(1_000_000);
		Trader second = trader(1_000_000);
		OrderResponse resting = place(first, symbol, Side.BUY, 70_000, 2);
		awaitProcessed(symbol);
		stopEngine(symbol);

		OrderResponse early = place(first, symbol, Side.BUY, 71_000, 5);
		OrderResponse late = place(second, symbol, Side.BUY, 71_000, 5);
		OrderResponse canceled = place(second, symbol, Side.BUY, 71_000, 1);
		orderService.cancel(second.userId(), canceled.orderId());
		orderService.cancel(first.userId(), resting.orderId());
		place(seller, symbol, Side.SELL, 71_000, 7);
		assertThat(orderService.get(first.userId(), early.orderId()).status()).isEqualTo(OrderStatus.ACCEPTED);
		assertInvariants();

		matchingService.reload(symbol);
		awaitProcessed(symbol);
		assertThat(orderService.get(first.userId(), early.orderId()).status()).isEqualTo(OrderStatus.FILLED);
		assertThat(orderService.get(second.userId(), late.orderId()).filledQuantity()).isEqualTo(2);
		assertThat(orderService.get(second.userId(), canceled.orderId()).status()).isEqualTo(OrderStatus.CANCELED);
		assertThat(orderService.get(first.userId(), resting.orderId()).status()).isEqualTo(OrderStatus.CANCELED);
	}

	@Test
	@DisplayName("체결 저장이 계속 실패하면 건너뛰지 않고 종목을 멈추며, 원인을 없애면 이어서 처리한다")
	void haltsWhenSettlementKeepsFailing() {
		String symbol = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 5);
		Trader buyer = trader(1_000_000);
		jdbc.execute("CREATE TRIGGER fail_" + symbol + " BEFORE INSERT ON execution FOR EACH ROW WHEN (NEW.symbol = '"
			+ symbol + "') EXECUTE FUNCTION reject_modification()");
		// 체결되지 않는 주문까지 한 묶음으로 처리되게 엔진을 멈춘 동안 주문한다.
		stopEngine(symbol);
		OrderResponse sell = place(seller, symbol, Side.SELL, 71_000, 5);
		OrderResponse buy = place(buyer, symbol, Side.BUY, 71_000, 5);
		matchingService.reload(symbol);
		await(() -> "HALTED".equals(status(symbol)));
		// 묶음이 실패해도 앞선 명령은 하나씩 다시 처리된다.
		assertThat(orderService.get(seller.userId(), sell.orderId()).status()).isEqualTo(OrderStatus.OPEN);
		assertThat(orderService.get(buyer.userId(), buy.orderId()).status()).isEqualTo(OrderStatus.ACCEPTED);
		assertError(ErrorCode.STOCK_HALTED, () -> place(buyer, symbol, Side.BUY, 71_000, 1));
		assertInvariants();

		jdbc.execute("DROP TRIGGER fail_" + symbol + " ON execution");
		jdbc.update("UPDATE stock SET status = 'TRADABLE' WHERE symbol = ?", symbol);
		matchingService.reload(symbol);
		awaitProcessed(symbol);
		assertThat(orderService.get(buyer.userId(), buy.orderId()).status()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	@DisplayName("밀린 명령은 묶어서 커밋하고 시간 우선은 그대로 지킨다")
	void settlesInBatches() {
		String symbol = stock();
		Trader buyer = trader(10_000_000);
		stopEngine(symbol);
		List<OrderResponse> orders = IntStream.range(0, 60)
			.mapToObj(i -> place(buyer, symbol, Side.BUY, 71_000, 1)).toList();
		matchingService.reload(symbol);
		awaitProcessed(symbol);
		// xmin은 행을 마지막으로 바꾼 트랜잭션이다.
		assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT xmin::text) FROM orders WHERE symbol = ?", Integer.class,
			symbol)).isEqualTo(3);
		assertThat(jdbc.queryForList("SELECT order_id FROM orders WHERE symbol = ? ORDER BY engine_seq", Long.class,
			symbol)).isEqualTo(orders.stream().map(OrderResponse::orderId).toList());
	}

	@Test
	@DisplayName("엔진과 DB의 순번이 어긋나면 DB 기준으로 호가창을 다시 만든다")
	void recoversFromSequenceMismatch() {
		String symbol = stock();
		Trader buyer = trader(1_000_000);
		place(buyer, symbol, Side.BUY, 71_000, 1);
		awaitProcessed(symbol);
		jdbc.update("UPDATE stock SET last_event_seq = last_event_seq + 5 WHERE symbol = ?", symbol);
		place(buyer, symbol, Side.BUY, 71_000, 2);
		awaitProcessed(symbol);
		assertThat(matchingService.snapshot(symbol).bids()).containsExactly(new BookSnapshot.Level(71_000, 3));
		assertThat(status(symbol)).isEqualTo("TRADABLE");
	}

	@Test
	@DisplayName("주문, 체결, 원장, 감사 로그의 시각은 모두 DB 시계로 남아 전후 관계가 뒤집히지 않는다")
	void timestampsUseDatabaseClock() {
		String symbol = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 1);
		Trader buyer = trader(1_000_000);
		place(seller, symbol, Side.SELL, 71_000, 1);
		OrderResponse buy = place(buyer, symbol, Side.BUY, 71_000, 1);
		awaitProcessed(symbol);
		assertThat(jdbc.queryForObject("""
			SELECT o.created_at <= e.executed_at AND e.executed_at <= l.created_at AND e.executed_at <= o.updated_at
			       AND l.created_at <= clock_timestamp() AND o.updated_at <= clock_timestamp()
			  FROM orders o JOIN execution e ON e.buy_order_id = o.order_id
			  JOIN cash_ledger l ON l.type = 'BUY' AND l.ref_type = 'EXECUTION' AND l.ref_id = e.execution_id
			 WHERE o.order_id = ?
			""", Boolean.class, buy.orderId())).isTrue();
		assertThat(jdbc.queryForObject("SELECT max(created_at) <= clock_timestamp() FROM audit_log", Boolean.class))
			.isTrue();
	}

	@Test
	@DisplayName("원장, 체결, 감사 로그는 DB에서 직접 수정하거나 지울 수 없다")
	void ledgersAreAppendOnly() {
		for (String table : List.of("cash_ledger", "position_ledger", "execution", "audit_log")) {
			for (String sql : List.of("DELETE FROM " + table, "TRUNCATE " + table,
				"UPDATE " + table + " SET " + (table.equals("audit_log") ? "result = 'x'"
					: table.equals("execution") ? "price = 1" : "reserved_delta = 1"))) {
				assertThatThrownBy(() -> jdbc.execute(sql)).hasMessageContaining("append-only");
			}
		}
	}

	// 엔진이 죽은 상황을 만든다. 주문은 계속 받지만 처리하지 않는다.
	private void stopEngine(String symbol) {
		jdbc.update("UPDATE stock SET status = 'HALTED' WHERE symbol = ?", symbol);
		matchingService.reload(symbol);
		// 워커가 정지 상태를 읽을 때까지 기다린 뒤 주문 접수만 다시 연다.
		long marker = System.nanoTime();
		await(() -> System.nanoTime() - marker > 500_000_000L);
		jdbc.update("UPDATE stock SET status = 'TRADABLE' WHERE symbol = ?", symbol);
	}

	// 계좌 잔고.
	private AccountResponse account(Trader trader) {
		return accountService.get(trader.userId(), trader.accountId());
	}

	// 종목 상태.
	private String status(String symbol) {
		return jdbc.queryForObject("SELECT status FROM stock WHERE symbol = ?", String.class, symbol);
	}

	// 주문을 낸 사용자.
	private static long owner(List<Trader> traders, OrderResponse order) {
		return traders.stream().filter(t -> t.accountId() == order.accountId()).findFirst().orElseThrow().userId();
	}

	// 지정한 에러 코드로 실패하는지 확인한다.
	private static void assertError(ErrorCode code, Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(code));
	}
}
