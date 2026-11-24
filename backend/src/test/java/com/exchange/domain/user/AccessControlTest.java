package com.exchange.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.exchange.IntegrationTest;
import com.exchange.domain.order.dto.OrderResponse;
import com.exchange.domain.user.entity.Role;
import com.exchange.domain.user.entity.User;
import com.exchange.engine.Side;

/** 로그인, 계정 잠금, CSRF, 타인 계좌 접근(IDOR)을 공격자 입장에서 검증한다. */
class AccessControlTest extends IntegrationTest {

	@Test
	@DisplayName("로그인 응답에 비밀번호 해시가 없고, 로그아웃하면 세션이 폐기된다")
	void loginAndLogout() throws Exception {
		Trader trader = trader(0);
		MockHttpSession session = new MockHttpSession();
		login(trader.loginId(), PASSWORD, session).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.loginId").value(trader.loginId()))
			.andExpect(jsonPath("$.data.passwordHash").doesNotExist());
		mockMvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("CUSTOMER"));
		mockMvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isOk());
		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	@DisplayName("다섯 번 틀리면 잠기고, 맞는 비밀번호와 없는 아이디도 같은 응답을 받는다")
	void locksAfterFailures() throws Exception {
		Trader trader = trader(0);
		for (int i = 0; i < 5; i++) {
			login(trader.loginId(), "wrong", new MockHttpSession()).andExpect(status().isUnauthorized());
		}
		login(trader.loginId(), PASSWORD, new MockHttpSession()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));
		login("nobody", PASSWORD, new MockHttpSession()).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE user_id = ? AND result = 'LOGIN_FAILED'",
			Integer.class, trader.userId())).isEqualTo(6);

		jdbc.update("UPDATE users SET locked_until = now() - interval '1 second' WHERE user_id = ?", trader.userId());
		login(trader.loginId(), PASSWORD, new MockHttpSession()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("아이디 없이 로그인해도 서버 오류가 나지 않는다")
	void loginWithoutId() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login").with(csrf())).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("CSRF 토큰이 없으면 로그인과 주문이 막힌다")
	void csrfIsRequired() throws Exception {
		Trader trader = trader(0);
		mockMvc.perform(post("/api/v1/auth/login").param("loginId", trader.loginId()).param("password", PASSWORD))
			.andExpect(status().isForbidden());
		mockMvc.perform(order(trader, trader.accountId()).session(session(trader))).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("다른 사람의 계좌는 조회도 변경도 할 수 없고 존재 여부도 알 수 없다")
	void cannotAccessOthersAccount() throws Exception {
		Trader victim = trader(1_000_000);
		Trader attacker = trader(1_000_000);
		MockHttpSession session = session(attacker);
		String account = "/api/v1/accounts/" + victim.accountId();
		for (MockHttpServletRequestBuilder attack : List.of(get(account), get(account + "/positions"),
			get(account + "/ledgers"), get("/api/v1/orders?accountId=" + victim.accountId()),
			get("/api/v1/executions?accountId=" + victim.accountId()), order(attacker, victim.accountId()),
			post(account + "/deposits").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":1}"),
			get("/api/v1/accounts/999999999"))) {
			mockMvc.perform(attack.session(session).with(csrf())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
		}
		mockMvc.perform(get("/api/v1/accounts").session(session)).andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].accountId").value(attacker.accountId()));
	}

	@Test
	@DisplayName("다른 사람의 주문은 조회도 취소도 할 수 없다")
	void cannotAccessOthersOrder() throws Exception {
		Trader victim = trader(1_000_000);
		OrderResponse order = place(victim, stock(), Side.BUY, 71_000, 1);
		MockHttpSession session = session(trader(0));
		for (MockHttpServletRequestBuilder attack : List.of(get("/api/v1/orders/" + order.orderId()),
			delete("/api/v1/orders/" + order.orderId()))) {
			mockMvc.perform(attack.session(session).with(csrf())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
		}
		assertThat(orderService.get(victim.userId(), order.orderId()).cancelRequested()).isFalse();
	}

	@Test
	@DisplayName("주식 입고는 운영자만 할 수 있고, 운영자도 고객 계좌를 조회하거나 시스템 계좌에 입고할 수 없다")
	void stockDepositIsOperatorOnly() throws Exception {
		Trader trader = trader(0);
		String symbol = stock();
		String path = "/api/v1/accounts/" + trader.accountId() + "/stock-deposits";
		mockMvc.perform(stockDeposit(path, symbol).session(session(trader)).with(csrf()))
			.andExpect(status().isForbidden());
		User operator = user(Role.OPERATOR);
		MockHttpSession session = new MockHttpSession();
		login(operator.getLoginId(), PASSWORD, session);
		mockMvc.perform(stockDeposit(path, symbol).session(session).with(csrf())).andExpect(status().isCreated());
		mockMvc.perform(get("/api/v1/accounts/" + trader.accountId()).session(session)).andExpect(status().isNotFound());
		long external = jdbc.queryForObject("SELECT account_id FROM account WHERE account_type = 'EXTERNAL'", Long.class);
		mockMvc.perform(stockDeposit("/api/v1/accounts/" + external + "/stock-deposits", symbol).session(session)
			.with(csrf())).andExpect(status().isNotFound());
		assertInvariants();
	}

	@Test
	@DisplayName("잘못된 요청은 400으로 거부하고 입력값이나 내부 정보를 되돌려 주지 않는다")
	void invalidRequests() throws Exception {
		Trader trader = trader(1_000_000);
		MockHttpSession session = session(trader);
		String body = "{\"accountId\":%d,\"symbol\":\"900010\",\"side\":\"BUY\",\"orderType\":\"%s\",\"price\":%s,\"quantity\":%s}";
		for (String content : List.of(body.formatted(trader.accountId(), "LIMIT", "-1", "1"),
			body.formatted(trader.accountId(), "LIMIT", "71000", "0"),
			body.formatted(trader.accountId(), "LIMIT", "71000", "1.5"),
			body.formatted(trader.accountId(), "LIMIT", "71000", "99999999999999999999"),
			body.formatted(trader.accountId(), "MARKET", "71000", "1"),
			body.formatted(trader.accountId(), "LIMIT", "71000", "1").replace("\"BUY\"", "1"), "{", "null")) {
			mockMvc.perform(post("/api/v1/orders").session(session).with(csrf()).header("Idempotency-Key", key())
				.contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
		}
		// 멱등키 누락
		mockMvc.perform(post("/api/v1/orders").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(body.formatted(trader.accountId(), "LIMIT", "71000", "1"))).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/orders?accountId=" + trader.accountId() + "&size=1000").session(session))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/none").session(session)).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE user_id = ? AND result = 'INVALID_REQUEST'",
			Integer.class, trader.userId())).isEqualTo(9);
	}

	@Test
	@DisplayName("호가창과 공개 체결에는 계좌 정보가 없다")
	void marketDataHasNoAccount() throws Exception {
		String symbol = stock();
		Trader buyer = trader(1_000_000);
		Trader seller = trader(0);
		giveStock(seller, symbol, 1);
		place(seller, symbol, Side.SELL, 71_000, 1);
		place(buyer, symbol, Side.BUY, 71_000, 1);
		awaitProcessed(symbol);
		MockHttpSession session = session(buyer);
		mockMvc.perform(get("/api/v1/stocks/" + symbol + "/trades").session(session)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].price").value(71_000)).andExpect(jsonPath("$.data[0].length()").value(4));
		mockMvc.perform(get("/api/v1/orderbooks/" + symbol).session(session)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/orderbooks/NONE").session(session)).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/executions?accountId=" + buyer.accountId()).session(session))
			.andExpect(jsonPath("$.data.items[0].side").value("BUY"));
	}

	// 로그인 요청을 보낸다.
	private ResultActions login(String loginId, String password, MockHttpSession session) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
			.param("loginId", loginId).param("password", password));
	}

	// 로그인한 세션을 만든다.
	private MockHttpSession session(Trader trader) throws Exception {
		MockHttpSession session = new MockHttpSession();
		login(trader.loginId(), PASSWORD, session).andExpect(status().isOk());
		return session;
	}

	// 주문 요청을 만든다.
	private MockHttpServletRequestBuilder order(Trader trader, long accountId) {
		return post("/api/v1/orders").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"accountId\":%d,\"symbol\":\"900010\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":71000,\"quantity\":1}"
				.formatted(accountId));
	}

	// 주식 입고 요청을 만든다.
	private MockHttpServletRequestBuilder stockDeposit(String path, String symbol) {
		return post(path).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"symbol\":\"%s\",\"quantity\":1}".formatted(symbol));
	}
}
