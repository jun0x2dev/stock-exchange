package com.exchange.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.exchange.domain.audit.entity.AuditLog;
import com.exchange.domain.execution.entity.Execution;
import com.exchange.domain.ledger.entity.CashLedger;
import com.exchange.domain.ledger.entity.PositionLedger;
import com.exchange.domain.order.dto.OrderResponse;
import com.exchange.domain.order.entity.OrderStatus;
import com.exchange.domain.outbox.entity.Outbox;
import com.exchange.engine.BookSnapshot;
import com.exchange.engine.Side;
import com.exchange.global.error.BusinessException;
import com.exchange.global.error.ErrorCode;

import jakarta.persistence.Column;

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
		// 명령 기록을 처음부터 다시 처리한 엔진의 호가창이 DB에 커밋된 주문과 같아야 한다.
		restartEngine();
		engine.close();
		for (String symbol : symbols) {
			BookSnapshot book = orderService.book(symbol);
			assertThat(engine.snapshot(symbol, 10)).isEqualTo(book);
		}
		engine.start();
	}

	@Test
	@DisplayName("엔진이 멈춘 동안 커밋된 주문과 취소는 다시 시작하면 순서대로 처리된다")
	void engineRestartProcessesCommittedOrders() throws Exception {
		String symbol = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 10);
		Trader first = trader(1_000_000);
		Trader second = trader(1_000_000);
		OrderResponse resting = place(first, symbol, Side.BUY, 70_000, 2);
		awaitProcessed(symbol);
		engine.close();

		OrderResponse early = place(first, symbol, Side.BUY, 71_000, 5);
		OrderResponse late = place(second, symbol, Side.BUY, 71_000, 5);
		OrderResponse canceled = place(second, symbol, Side.BUY, 71_000, 1);
		orderService.cancel(second.userId(), canceled.orderId());
		orderService.cancel(first.userId(), resting.orderId());
		place(seller, symbol, Side.SELL, 71_000, 7);
		assertThat(orderService.get(first.userId(), early.orderId()).status()).isEqualTo(OrderStatus.ACCEPTED);
		assertInvariants();

		engine.start();
		awaitProcessed(symbol);
		assertThat(orderService.get(first.userId(), early.orderId()).status()).isEqualTo(OrderStatus.FILLED);
		assertThat(orderService.get(second.userId(), late.orderId()).filledQuantity()).isEqualTo(2);
		assertThat(orderService.get(second.userId(), canceled.orderId()).status()).isEqualTo(OrderStatus.CANCELED);
		assertThat(orderService.get(first.userId(), resting.orderId()).status()).isEqualTo(OrderStatus.CANCELED);
	}

	@Test
	@DisplayName("core가 멈춘 동안 엔진이 낸 결과는 다시 시작하면 빠짐없이 반영된다")
	void settlementRestartAppliesPendingResults() {
		String symbol = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 5);
		Trader buyer = trader(1_000_000);
		stopSettlement();
		place(seller, symbol, Side.SELL, 71_000, 5);
		OrderResponse buy = place(buyer, symbol, Side.BUY, 71_000, 5);
		await(() -> unpublished() == 0);
		assertThat(orderService.get(buyer.userId(), buy.orderId()).status()).isEqualTo(OrderStatus.ACCEPTED);
		startSettlement();
		awaitProcessed(symbol);
		assertThat(orderService.get(buyer.userId(), buy.orderId()).status()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	@DisplayName("발행 표시를 잃어 같은 명령이 다시 나가도 한 번만 체결된다")
	void duplicateCommandsAreIgnored() {
		String symbol = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 10);
		Trader buyer = trader(1_000_000);
		place(seller, symbol, Side.SELL, 71_000, 10);
		OrderResponse buy = place(buyer, symbol, Side.BUY, 71_000, 4);
		awaitProcessed(symbol);
		long seq = lastSeq(symbol);
		jdbc.update("UPDATE outbox SET published_at = NULL WHERE symbol = ?", symbol);
		await(() -> unpublished() == 0);
		// 뒤에 낸 주문이 처리됐으면 앞서 다시 나간 명령도 모두 지나간 것이다.
		place(buyer, symbol, Side.BUY, 50_000, 1);
		awaitProcessed(symbol);
		assertThat(lastSeq(symbol)).isEqualTo(seq + 1);
		assertThat(orderService.get(buyer.userId(), buy.orderId()).filledQuantity()).isEqualTo(4);
	}

	@Test
	@DisplayName("이미 반영한 결과를 처음부터 다시 읽어도 두 번 반영하지 않는다")
	void duplicateResultsAreIgnored() {
		String symbol = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 10);
		Trader buyer = trader(1_000_000);
		place(seller, symbol, Side.SELL, 71_000, 10);
		place(buyer, symbol, Side.BUY, 71_000, 4);
		OrderResponse canceled = place(buyer, symbol, Side.BUY, 50_000, 1);
		awaitProcessed(symbol);
		orderService.cancel(buyer.userId(), canceled.orderId());
		awaitProcessed(symbol);
		long seq = lastSeq(symbol);
		AccountResponse before = account(buyer);
		stopSettlement();
		// 정지를 푼 종목처럼 만들어 모든 파티션을 처음부터 다시 읽게 한다.
		for (int partition = 0; partition < 3; partition++) {
			jdbc.update("INSERT INTO stock (symbol, name, base_price, halt_partition, halt_offset) VALUES (?, ?, 1000, ?, 0)",
				"R" + partition + symbol, "재처리", partition);
		}
		startSettlement();
		place(buyer, symbol, Side.BUY, 50_000, 1);
		awaitProcessed(symbol);
		jdbc.update("UPDATE stock SET halt_partition = NULL, halt_offset = NULL");
		assertThat(status(symbol)).isEqualTo("TRADABLE");
		assertThat(lastSeq(symbol)).isEqualTo(seq + 1);
		assertThat(account(buyer).cashBalance()).isEqualTo(before.cashBalance());
	}

	@Test
	@DisplayName("중간 결과가 빠지면 건너뛰지 않고 종목을 멈추며, 다시 열면 빠진 결과부터 반영한다")
	void haltsOnMissingResult() {
		String symbol = stock();
		Trader buyer = trader(1_000_000);
		place(buyer, symbol, Side.BUY, 71_000, 1);
		awaitProcessed(symbol);
		// 반영했다고 기록된 순번을 되돌려 결과 하나를 잃은 상황을 만든다.
		jdbc.update("ALTER TABLE orders DISABLE TRIGGER set_updated_at");
		jdbc.update("UPDATE orders SET status = 'ACCEPTED', engine_seq = NULL WHERE symbol = ?", symbol);
		jdbc.update("ALTER TABLE orders ENABLE TRIGGER set_updated_at");
		jdbc.update("UPDATE stock SET last_event_seq = 0 WHERE symbol = ?", symbol);
		place(buyer, symbol, Side.BUY, 71_000, 2);
		await(() -> "HALTED".equals(status(symbol)));
		assertError(ErrorCode.STOCK_HALTED, () -> place(buyer, symbol, Side.BUY, 71_000, 1));
		assertInvariants();

		resume(symbol);
		awaitProcessed(symbol);
		assertThat(orderService.book(symbol).bids()).containsExactly(new BookSnapshot.Level(71_000, 3));
	}

	@Test
	@DisplayName("DB보다 앞선 순번의 다른 결과가 오면 중복으로 착각하지 않고 멈춘다")
	void haltsWhenResultDiffersFromApplied() {
		String symbol = stock();
		Trader buyer = trader(1_000_000);
		// Kafka 기록을 잃고 엔진이 순번을 처음부터 다시 센 상황. DB에는 이미 5번까지 반영돼 있다.
		jdbc.update("UPDATE stock SET last_event_seq = 5 WHERE symbol = ?", symbol);
		OrderResponse order = place(buyer, symbol, Side.BUY, 71_000, 1);
		await(() -> "HALTED".equals(status(symbol)));
		assertThat(orderService.get(buyer.userId(), order.orderId()).status()).isEqualTo(OrderStatus.ACCEPTED);
		jdbc.update("UPDATE stock SET last_event_seq = 0 WHERE symbol = ?", symbol);
		resume(symbol);
		awaitProcessed(symbol);
	}

	@Test
	@DisplayName("체결 저장이 계속 실패하면 그 종목만 멈추고, 원인을 없애고 다시 열면 이어서 처리한다")
	void haltsWhenSettlementKeepsFailing() {
		String symbol = stock();
		String healthy = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 5);
		Trader buyer = trader(1_000_000);
		jdbc.execute("CREATE TRIGGER fail_" + symbol + " BEFORE INSERT ON execution FOR EACH ROW WHEN (NEW.symbol = '"
			+ symbol + "') EXECUTE FUNCTION reject_modification()");
		engine.close();
		OrderResponse sell = place(seller, symbol, Side.SELL, 71_000, 5);
		OrderResponse buy = place(buyer, symbol, Side.BUY, 71_000, 5);
		engine.start();
		await(() -> "HALTED".equals(status(symbol)));
		// 묶음이 실패해도 앞선 결과는 하나씩 다시 반영된다.
		assertThat(orderService.get(seller.userId(), sell.orderId()).status()).isEqualTo(OrderStatus.OPEN);
		assertThat(orderService.get(buyer.userId(), buy.orderId()).status()).isEqualTo(OrderStatus.ACCEPTED);
		assertError(ErrorCode.STOCK_HALTED, () -> place(buyer, symbol, Side.BUY, 71_000, 1));
		place(buyer, healthy, Side.BUY, 71_000, 1);
		awaitProcessed(healthy);
		assertInvariants();

		jdbc.execute("DROP TRIGGER fail_" + symbol + " ON execution");
		resume(symbol);
		awaitProcessed(symbol);
		assertThat(orderService.get(buyer.userId(), buy.orderId()).status()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	@DisplayName("밀린 명령은 묶어서 커밋하고 시간 우선은 그대로 지킨다")
	void settlesInBatches() {
		String symbol = stock();
		Trader buyer = trader(10_000_000);
		engine.close();
		List<OrderResponse> orders = IntStream.range(0, 60)
			.mapToObj(i -> place(buyer, symbol, Side.BUY, 71_000, 1)).toList();
		await(() -> unpublished() == 0);
		engine.start();
		awaitProcessed(symbol);
		// xmin은 행을 마지막으로 바꾼 트랜잭션이다. Kafka가 나눠 주는 크기에 따라 묶음 수는 달라진다.
		assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT xmin::text) FROM orders WHERE symbol = ?", Integer.class,
			symbol)).isLessThan(30);
		assertThat(jdbc.queryForList("SELECT order_id FROM orders WHERE symbol = ? ORDER BY engine_seq", Long.class,
			symbol)).isEqualTo(orders.stream().map(OrderResponse::orderId).toList());
	}

	@Test
	@DisplayName("주문, 체결, 원장, 감사 로그의 시각은 앱이 아니라 DB가 채운다")
	void timestampsComeFromDatabase() {
		// 시계가 뒤로 보정될 수 있어 시각의 크기를 비교하지 않고, 값을 채우는 주체를 확인한다.
		for (String column : List.of("orders.created_at", "execution.executed_at", "cash_ledger.created_at",
			"position_ledger.created_at", "audit_log.created_at", "outbox.created_at")) {
			String[] parts = column.split("\\.");
			assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
				+ "WHERE table_name = ? AND column_name = ?", String.class, parts[0], parts[1]))
				.as(column).startsWith("clock_timestamp()");
		}
		for (Class<?> entity : List.of(Execution.class, CashLedger.class, PositionLedger.class, AuditLog.class, Outbox.class)) {
			assertThat(Arrays.stream(entity.getDeclaredFields())
				.filter(f -> f.getName().equals("createdAt") || f.getName().equals("executedAt"))
				.map(f -> f.getAnnotation(Column.class).insertable())).as(entity.getSimpleName()).containsExactly(false);
		}
	}

	@Test
	@DisplayName("원장, 체결, 감사 로그는 DB에서 직접 수정하거나 지울 수 없다")
	void ledgersAreAppendOnly() {
		for (String table : List.of("cash_ledger", "position_ledger", "execution", "audit_log", "audit_chain")) {
			for (String sql : List.of("DELETE FROM " + table, "TRUNCATE " + table + " CASCADE",
				"UPDATE " + table + " SET " + (table.equals("audit_log") ? "result = 'x'"
					: table.equals("audit_chain") ? "hash = 'x'"
					: table.equals("execution") ? "price = 1" : "reserved_delta = 1"))) {
				assertThatThrownBy(() -> jdbc.execute(sql)).hasMessageContaining("append-only");
			}
		}
	}

	// 엔진을 껐다 켜고 호가창이 되살아날 때까지 기다린다.
	private void restartEngine() throws InterruptedException {
		engine.close();
		engine.start();
		assertThat(engine.awaitReplayed(Duration.ofSeconds(30))).isTrue();
	}

	// 운영자가 정지를 풀고 core를 재시작한 상황을 만든다.
	private void resume(String symbol) {
		stopSettlement();
		jdbc.update("UPDATE stock SET status = 'TRADABLE' WHERE symbol = ?", symbol);
		startSettlement();
	}

	// 아직 Kafka로 보내지 않은 명령 수.
	private int unpublished() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL", Integer.class);
	}

	// 종목에 반영된 마지막 순번.
	private long lastSeq(String symbol) {
		return jdbc.queryForObject("SELECT last_event_seq FROM stock WHERE symbol = ?", Long.class, symbol);
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
