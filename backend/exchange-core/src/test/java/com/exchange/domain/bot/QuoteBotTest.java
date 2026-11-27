package com.exchange.domain.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.exchange.IntegrationTest;
import com.exchange.domain.bot.service.QuoteBotService;
import com.exchange.engine.BookSnapshot.Level;
import com.exchange.engine.Side;
import com.exchange.global.error.BusinessException;

/** 봇이 시세를 따라 호가를 내고 거두는지, 그 과정에서 불변식이 유지되는지 검증한다. */
class QuoteBotTest extends IntegrationTest {

	@Autowired
	private QuoteBotService bot;

	private String symbol;
	private Trader maker;

	@BeforeEach
	void prepareMaker() {
		symbol = stock();
		maker = botUser("maker", 100_000_000);
		giveStock(maker, symbol, 1_000);
	}

	@AfterEach
	void invariantsHold() {
		awaitProcessed(symbol);
		assertInvariants();
		// 같은 로그인 아이디를 다른 테스트에서 다시 쓸 수 있게 한다.
		jdbc.update("UPDATE users SET login_id = login_id || user_id WHERE login_id IN ('maker', 'taker')");
	}

	@Test
	@DisplayName("시세 양쪽에 한 호가씩 떨어진 3단계를 낸다")
	void quotesThreeLevels() {
		bot.quote(symbol, 71_000);
		awaitProcessed(symbol);
		assertThat(orderService.book(symbol).asks()).extracting(Level::price).containsExactly(71_100L, 71_200L, 71_300L);
		assertThat(orderService.book(symbol).bids()).extracting(Level::price).containsExactly(70_900L, 70_800L, 70_700L);
	}

	@Test
	@DisplayName("한 번에 내는 호가는 매수를 모두 낸 뒤 매도를 낸다. 체결 반영과 잠금 순서가 같아 교착되지 않는다")
	void locksAccountBeforePosition() {
		bot.quote(symbol, 71_000);
		assertThat(jdbc.queryForList("SELECT side FROM orders WHERE symbol = ? ORDER BY order_id", String.class, symbol))
			.containsExactly("BUY", "BUY", "BUY", "SELL", "SELL", "SELL");
	}

	@Test
	@DisplayName("시세가 그대로면 주문을 새로 내지 않고, 움직이면 바뀐 호가만 바꾼다")
	void changesOnlyWhatMoved() {
		bot.quote(symbol, 71_000);
		awaitProcessed(symbol);
		bot.quote(symbol, 71_000);
		assertThat(orderCount()).isEqualTo(6);
		bot.quote(symbol, 71_100);
		awaitProcessed(symbol);
		// 매수는 71,000원을 더하고 매도는 71,100원을 거두는 식으로 한쪽에 하나씩 바뀐다.
		assertThat(orderCount()).isEqualTo(8);
		assertThat(orderService.book(symbol).bids()).extracting(Level::price).containsExactly(71_000L, 70_900L, 70_800L);
		assertThat(orderService.book(symbol).asks()).extracting(Level::price).containsExactly(71_200L, 71_300L, 71_400L);
	}

	@Test
	@DisplayName("시세가 크게 뛰어도 봇끼리 체결되지 않는다")
	void neverTradesWithItself() {
		bot.quote(symbol, 71_000);
		bot.quote(symbol, 75_000);
		bot.quote(symbol, 68_000);
		awaitProcessed(symbol);
		assertThat(executions()).isZero();
		assertThat(orderService.book(symbol).asks()).hasSize(3);
		assertThat(orderService.book(symbol).bids()).hasSize(3);
	}

	@Test
	@DisplayName("시세가 끊기면 호가를 모두 거두고 묶인 돈과 주식을 돌려받는다")
	void withdrawsAll() {
		bot.quote(symbol, 71_000);
		awaitProcessed(symbol);
		bot.withdraw(symbol);
		awaitProcessed(symbol);
		assertThat(orderService.book(symbol).asks()).isEmpty();
		assertThat(orderService.book(symbol).bids()).isEmpty();
		assertThat(accountService.get(maker.userId(), maker.accountId()).reservedCash()).isZero();
	}

	@Test
	@DisplayName("절반 넘게 체결된 호가는 새로 채운다")
	void refillsConsumedLevel() {
		bot.quote(symbol, 71_000);
		awaitProcessed(symbol);
		Level best = orderService.book(symbol).asks().getFirst();
		Trader buyer = trader(100_000_000);
		place(buyer, symbol, Side.BUY, best.price(), best.quantity() - 1);
		awaitProcessed(symbol);
		bot.quote(symbol, 71_000);
		awaitProcessed(symbol);
		assertThat(orderService.book(symbol).asks().getFirst()).isEqualTo(best);
	}

	@Test
	@DisplayName("새 호가가 거부돼도 옛 호가는 거둔다")
	void cancelsStaleEvenWhenPlacingFails() {
		bot.quote(symbol, 71_000);
		awaitProcessed(symbol);
		// 상한가를 넘는 시세라 새 호가는 거부된다.
		assertThatThrownBy(() -> bot.quote(symbol, 92_300)).isInstanceOf(BusinessException.class);
		awaitProcessed(symbol);
		assertThat(orderService.book(symbol).bids()).isEmpty();
		assertThat(orderService.book(symbol).asks()).isEmpty();
	}

	@Test
	@DisplayName("taker는 최우선 호가로 소량 거래하고, 상대 호가가 없으면 주문하지 않는다")
	void takerTrades() {
		botUser("taker", 10_000_000);
		bot.trade(symbol, true, 5);
		assertThat(orderCount()).isZero();
		bot.quote(symbol, 71_000);
		awaitProcessed(symbol);
		bot.trade(symbol, true, 5);
		awaitProcessed(symbol);
		assertThat(executions()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT price FROM execution WHERE symbol = ?", Long.class, symbol))
			.isEqualTo(71_100L);
	}

	// 봇이 찾는 로그인 아이디를 가진 사용자와 계좌를 만든다.
	private Trader botUser(String loginId, long cash) {
		Trader trader = trader(cash);
		jdbc.update("UPDATE users SET login_id = ? WHERE user_id = ?", loginId, trader.userId());
		return trader;
	}

	// 종목의 주문 수.
	private int orderCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE symbol = ?", Integer.class, symbol);
	}

	// 종목의 체결 수.
	private int executions() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM execution WHERE symbol = ?", Integer.class, symbol);
	}
}
