package com.exchange.global.demo;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.exchange.domain.account.dto.DepositRequest;
import com.exchange.domain.account.dto.StockDepositRequest;
import com.exchange.domain.account.entity.Account;
import com.exchange.domain.account.repository.AccountRepository;
import com.exchange.domain.account.service.AccountService;
import com.exchange.domain.order.dto.PlaceOrderRequest;
import com.exchange.domain.order.entity.OrderType;
import com.exchange.domain.order.service.OrderService;
import com.exchange.domain.stock.dto.StockResponse;
import com.exchange.market.PricePolicy;
import com.exchange.domain.stock.service.StockService;
import com.exchange.domain.user.entity.Role;
import com.exchange.domain.user.entity.User;
import com.exchange.domain.user.repository.UserRepository;
import com.exchange.engine.Side;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로컬 체험용 사용자, 잔고, 호가를 만든다. 비밀번호를 설정했을 때만 동작한다.
 *
 * <p>멱등키를 고정해 다시 실행해도 중복되지 않으며, 실제 주문과 같은 경로로 넣어 불변식을 지킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

	private static final int LEVELS = 10;
	private static final int TRADES = 24;

	private final UserRepository userRepository;
	private final AccountRepository accountRepository;
	private final AccountService accountService;
	private final OrderService orderService;
	private final StockService stockService;
	private final PasswordEncoder passwordEncoder;

	@Value("${exchange.demo.password}")
	private String password;

	@Override
	public void run(ApplicationArguments args) {
		if (!StringUtils.hasText(password)) {
			return;
		}
		user("operator", Role.OPERATOR);
		user("approver", Role.APPROVER);
		user("auditor", Role.AUDITOR);
		Trader demo = trader("demo");
		Trader maker = trader("maker");
		Trader taker = trader("taker");
		accountService.deposit(demo.userId, demo.accountId, "demo-cash", new DepositRequest(10_000_000L));
		List<StockResponse> stocks = stockService.findAll();
		accountService.depositStock(demo.accountId, "demo-stock",
			new StockDepositRequest(stocks.getFirst().symbol(), 20L));
		for (Trader trader : List.of(maker, taker)) {
			accountService.deposit(trader.userId, trader.accountId, "demo-cash", new DepositRequest(1_000_000_000L));
			stocks.forEach(stock -> accountService.depositStock(trader.accountId, "demo-" + stock.symbol(),
				new StockDepositRequest(stock.symbol(), 10_000L)));
		}
		stocks.forEach(stock -> seedMarket(stock, maker, taker));
		log.info("체험용 데이터 준비 완료");
	}

	// 체결 몇 건과 양쪽 10단계 호가를 만든다.
	private void seedMarket(StockResponse stock, Trader maker, Trader taker) {
		long price = stock.basePrice();
		for (int i = 0; i < TRADES; i++) {
			// 종목마다 다르지만 항상 같은 흐름이 나오도록 코드와 순서로 방향을 정한다.
			int step = Math.floorMod((stock.symbol() + i).hashCode() * 31, 5) - 2;
			price += step * PricePolicy.tickSize(price);
			long quantity = 1 + Math.floorMod((i + stock.symbol()).hashCode(), 17);
			place(maker, stock, Side.SELL, price, quantity, "walk-" + i);
			place(taker, stock, Side.BUY, price, quantity, "walk-" + i);
		}
		long bid = price;
		long ask = price;
		for (int i = 0; i < LEVELS; i++) {
			bid -= PricePolicy.tickSize(bid - 1);
			ask += PricePolicy.tickSize(ask);
			place(maker, stock, Side.BUY, bid, 10 + Math.floorMod((i + "b" + stock.symbol()).hashCode(), 90), "bid-" + i);
			place(maker, stock, Side.SELL, ask, 10 + Math.floorMod((i + "a" + stock.symbol()).hashCode(), 90), "ask-" + i);
		}
	}

	// 고정된 멱등키로 주문한다.
	private void place(Trader trader, StockResponse stock, Side side, long price, long quantity, String key) {
		orderService.place(trader.userId, "demo-" + stock.symbol() + "-" + key, new PlaceOrderRequest(
			trader.accountId, stock.symbol(), side, OrderType.LIMIT, price, quantity));
	}

	// 사용자와 계좌를 찾거나 만든다.
	private Trader trader(String loginId) {
		User user = user(loginId, Role.CUSTOMER);
		Account account = accountRepository.findByUserIdOrderByAccountId(user.getUserId()).stream().findFirst()
			.orElseGet(() -> accountRepository.save(Account.create(user.getUserId())));
		return new Trader(user.getUserId(), account.getAccountId());
	}

	// 사용자를 찾거나 만든다.
	private User user(String loginId, Role role) {
		return userRepository.findByLoginId(loginId)
			.orElseGet(() -> userRepository.save(User.create(loginId, passwordEncoder.encode(password), role)));
	}

	/** 체험용 사용자와 계좌. */
	private record Trader(long userId, long accountId) {
	}
}
