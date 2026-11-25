package com.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.exchange.domain.account.dto.DepositRequest;
import com.exchange.domain.account.dto.StockDepositRequest;
import com.exchange.domain.account.entity.Account;
import com.exchange.domain.account.repository.AccountRepository;
import com.exchange.domain.account.service.AccountService;
import com.exchange.domain.execution.listener.EngineResultListener;
import com.exchange.domain.order.dto.OrderResponse;
import com.exchange.domain.order.dto.PlaceOrderRequest;
import com.exchange.domain.order.entity.OrderType;
import com.exchange.domain.order.service.OrderService;
import com.exchange.domain.user.entity.Role;
import com.exchange.domain.user.entity.User;
import com.exchange.domain.user.repository.UserRepository;
import com.exchange.engine.Side;
import com.exchange.engine.app.EngineRunner;

/** 실제 PostgreSQL과 매칭 워커를 쓰는 통합 테스트의 공통 기능. 원장은 지울 수 없으므로 테스트마다 새 사용자와 종목을 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

	protected static final String PASSWORD = "test-password";

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	@Autowired
	protected MockMvc mockMvc;

	/** DB 소유자 연결. 애플리케이션 계정은 종목을 만들거나 잠금 시각을 바꿀 수 없다. */
	protected JdbcTemplate jdbc;

	@Autowired
	protected OrderService orderService;

	@Autowired
	protected AccountService accountService;

	@Autowired
	protected EngineRunner engine;

	@Autowired
	protected KafkaListenerEndpointRegistry listeners;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	/** DB 소유자 연결을 받는다. */
	@Autowired
	void setOwnerPool(@Qualifier("ownerPool") DataSource ownerPool) {
		jdbc = new JdbcTemplate(ownerPool);
	}

	/** 테스트 사용자와 계좌. */
	protected record Trader(long userId, long accountId, String loginId) {
	}

	/** 예수금을 가진 고객을 만든다. */
	protected Trader trader(long cash) {
		User user = user(Role.CUSTOMER);
		long accountId = accountRepository.save(Account.create(user.getUserId())).getAccountId();
		if (cash > 0) {
			accountService.deposit(user.getUserId(), accountId, key(), new DepositRequest(cash));
		}
		return new Trader(user.getUserId(), accountId, user.getLoginId());
	}

	/** 사용자를 만든다. */
	protected User user(Role role) {
		return userRepository.save(
			User.create("user" + SEQUENCE.incrementAndGet(), passwordEncoder.encode(PASSWORD), role));
	}

	/** 기준가 71,000원인 종목을 만든다. */
	protected String stock() {
		String symbol = "T" + SEQUENCE.incrementAndGet();
		jdbc.update("INSERT INTO stock (symbol, name, base_price) VALUES (?, ?, 71000)", symbol, "테스트" + symbol);
		return symbol;
	}

	/** 주식을 입고한다. */
	protected void giveStock(Trader trader, String symbol, long quantity) {
		accountService.depositStock(trader.accountId(), key(), new StockDepositRequest(symbol, quantity));
	}

	/** 새 멱등키로 주문한다. */
	protected OrderResponse place(Trader trader, String symbol, Side side, long price, long quantity) {
		return orderService.place(trader.userId(), key(), request(trader, symbol, side, price, quantity));
	}

	/** 주문 요청을 만든다. */
	protected PlaceOrderRequest request(Trader trader, String symbol, Side side, long price, long quantity) {
		return new PlaceOrderRequest(trader.accountId(), symbol, side, OrderType.LIMIT, price, quantity);
	}

	/** 새 멱등키. */
	protected String key() {
		return UUID.randomUUID().toString();
	}

	/** 엔진이 종목의 주문과 취소를 모두 처리할 때까지 기다린다. */
	protected void awaitProcessed(String symbol) {
		await(() -> jdbc.queryForObject("""
			SELECT COUNT(*) FROM orders WHERE symbol = ? AND (status = 'ACCEPTED'
			   OR (cancel_requested_at IS NOT NULL AND status IN ('OPEN', 'PARTIALLY_FILLED')))
			""", Integer.class, symbol) == 0);
	}

	/** 엔진 결과를 반영하는 리스너를 멈춘다. */
	protected void stopSettlement() {
		listeners.getListenerContainer(EngineResultListener.ID).stop();
	}

	/** 리스너를 다시 시작한다. core가 재시작한 것과 같이 파티션을 다시 배정받는다. */
	protected void startSettlement() {
		listeners.getListenerContainer(EngineResultListener.ID).start();
	}

	/** 조건이 참이 될 때까지 기다린다. */
	protected void await(Callable<Boolean> condition) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
		try {
			while (!condition.call()) {
				assertThat(Instant.now()).as("시간 안에 조건을 만족해야 한다").isBefore(deadline);
				Thread.sleep(20);
			}
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 작업들을 동시에 시작하고, 예외 없이 끝난 작업 수를 센다. */
	protected int runConcurrently(List<Runnable> tasks) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger succeeded = new AtomicInteger();
		try (ExecutorService executor = Executors.newFixedThreadPool(Math.min(tasks.size(), 10))) {
			List<Future<?>> futures = tasks.stream().<Future<?>>map(task -> executor.submit(() -> {
				start.await();
				task.run();
				succeeded.incrementAndGet();
				return null;
			})).toList();
			start.countDown();
			for (Future<?> future : futures) {
				try {
					future.get();
				} catch (java.util.concurrent.ExecutionException e) {
					// 업무 규칙에 막힌 요청만 실패로 인정하고, 그 밖의 예외는 드러낸다.
					assertThat(e.getCause()).isInstanceOf(com.exchange.global.error.BusinessException.class);
				}
			}
		}
		return succeeded.get();
	}

	/** 불변식 I1~I7이 모두 지켜지는지 대사 뷰로 검사한다. */
	protected void assertInvariants() {
		assertThat(jdbc.queryForList("SELECT invariant || ' ' || detail FROM invariant_violation", String.class))
			.isEmpty();
	}
}
