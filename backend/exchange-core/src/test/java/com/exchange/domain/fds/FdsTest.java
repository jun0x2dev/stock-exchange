package com.exchange.domain.fds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

import com.exchange.IntegrationTest;
import com.exchange.domain.fds.service.FdsService;
import com.exchange.domain.order.dto.OrderResponse;
import com.exchange.domain.user.entity.Role;
import com.exchange.engine.Side;

/** 허수성 호가 규칙이 스푸핑은 잡고, 비슷해 보이는 정상 거래는 잡지 않는지 검증한다. */
class FdsTest extends IntegrationTest {

	@Autowired
	private FdsService fdsService;

	private String symbol;
	private Trader market;
	private Trader suspect;

	// 다른 사람들의 보통 주문(10주)으로 호가창을 만든다. 매수 70,900원, 매도 71,100원이 최우선이다.
	@BeforeEach
	void market() {
		symbol = stock();
		market = trader(300_000_000);
		giveStock(market, symbol, 1_000);
		for (int i = 0; i < 5; i++) {
			place(market, symbol, Side.BUY, 70_900 - i * 100, 10);
			place(market, symbol, Side.SELL, 71_100 + i * 100, 10);
		}
		suspect = trader(500_000_000);
		giveStock(suspect, symbol, 5_000);
		awaitProcessed(symbol);
	}

	@Test
	@DisplayName("큰 매수 주문으로 호가를 두껍게 보인 뒤 매도로 체결하고 바로 취소하면 경보한다")
	void detectsSpoofing() {
		OrderResponse fake = place(suspect, symbol, Side.BUY, 70_800, 1_000);
		awaitProcessed(symbol);
		place(suspect, symbol, Side.SELL, 70_900, 3);
		cancel(fake);

		assertThat(fdsService.detect()).isPositive();
		assertThat(alerts()).containsExactly("SPOOFING order=" + fake.orderId());
		assertThat(jdbc.queryForObject("SELECT evidence FROM fds_alert WHERE order_id = ?", String.class, fake.orderId()))
			.contains("매수 1000주", "같은 쪽 잔량의 95%", "반대편 3주 체결");
		// 다시 검사해도 같은 주문은 한 번만 경보한다.
		fdsService.detect();
		assertThat(alerts()).hasSize(1);
		assertInvariants();
	}

	@Test
	@DisplayName("매도 쪽으로 해도 잡는다")
	void detectsSellSide() {
		OrderResponse fake = place(suspect, symbol, Side.SELL, 71_200, 1_000);
		awaitProcessed(symbol);
		place(suspect, symbol, Side.BUY, 71_100, 3);
		cancel(fake);
		fdsService.detect();
		assertThat(alerts()).containsExactly("SPOOFING order=" + fake.orderId());
	}

	@Test
	@DisplayName("큰 주문을 냈다가 거래 없이 취소하는 것만으로는 경보하지 않는다")
	void ignoresLargeCancelWithoutTrade() {
		cancel(place(suspect, symbol, Side.BUY, 70_000, 1_000));
		fdsService.detect();
		assertThat(alerts()).isEmpty();
	}

	@Test
	@DisplayName("큰 주문이라도 같은 쪽에 이미 더 큰 잔량이 있었으면 경보하지 않는다")
	void ignoresWhenBookIsAlreadyDeep() {
		place(market, symbol, Side.BUY, 70_700, 2_000);
		awaitProcessed(symbol);
		OrderResponse order = place(suspect, symbol, Side.BUY, 70_800, 1_000);
		awaitProcessed(symbol);
		place(suspect, symbol, Side.SELL, 70_900, 3);
		cancel(order);
		fdsService.detect();
		assertThat(alerts()).isEmpty();
	}

	@Test
	@DisplayName("보통 크기의 주문을 취소하고 반대편에서 거래하는 것은 경보하지 않는다")
	void ignoresNormalSizedCancel() {
		OrderResponse order = place(suspect, symbol, Side.BUY, 70_800, 20);
		awaitProcessed(symbol);
		place(suspect, symbol, Side.SELL, 70_900, 3);
		cancel(order);
		fdsService.detect();
		assertThat(alerts()).isEmpty();
	}

	@Test
	@DisplayName("시장 조성자처럼 거둔 호가보다 반대편에서 더 많이 거래했으면 경보하지 않는다")
	void ignoresMarketMaker() {
		OrderResponse quote = place(suspect, symbol, Side.BUY, 70_800, 60);
		awaitProcessed(symbol);
		// 반대편의 보통 주문 다섯 개(50주)를 모두 받아 거둔 호가(60주)와 비슷한 양을 거래한다.
		place(suspect, symbol, Side.SELL, 70_500, 50);
		cancel(quote);
		fdsService.detect();
		assertThat(alerts()).isEmpty();
	}

	@Test
	@DisplayName("오래 둔 뒤 취소했거나 대부분 체결된 큰 주문은 경보하지 않는다")
	void ignoresLongLivedOrFilled() {
		OrderResponse old = place(suspect, symbol, Side.BUY, 70_800, 1_000);
		awaitProcessed(symbol);
		place(suspect, symbol, Side.SELL, 70_900, 3);
		// 주문이 1분 전에 들어온 것으로 만든다.
		jdbc.update("UPDATE orders SET created_at = created_at - interval '1 minute' WHERE order_id = ?", old.orderId());
		cancel(old);

		OrderResponse filled = place(suspect, symbol, Side.SELL, 71_300, 100);
		place(market, symbol, Side.BUY, 71_300, 95);
		awaitProcessed(symbol);
		place(suspect, symbol, Side.BUY, 70_900, 3);
		cancel(filled);

		fdsService.detect();
		assertThat(alerts()).isEmpty();
	}

	@Test
	@DisplayName("경보는 고치거나 지울 수 없고, 직원만 볼 수 있다")
	void alertsAreProtected() throws Exception {
		OrderResponse fake = place(suspect, symbol, Side.BUY, 70_800, 1_000);
		awaitProcessed(symbol);
		place(suspect, symbol, Side.SELL, 70_900, 3);
		cancel(fake);
		fdsService.detect();
		for (String sql : List.of("UPDATE fds_alert SET evidence = ''", "DELETE FROM fds_alert")) {
			assertThatThrownBy(() -> jdbc.execute(sql)).hasMessageContaining("append-only");
		}
		mockMvc.perform(get("/api/v1/admin/fds-alerts").session(session(Role.CUSTOMER))).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/admin/fds-alerts?size=100").session(session(Role.AUDITOR)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[?(@.orderId == %d)].owner".formatted(fake.orderId()))
				.value(suspect.loginId()));
	}

	// 이 테스트의 종목에서 나온 경보.
	private List<String> alerts() {
		return jdbc.queryForList("SELECT rule || ' order=' || order_id FROM fds_alert WHERE symbol = ? ORDER BY alert_id",
			String.class, symbol);
	}

	// 엔진이 앞선 명령을 처리한 뒤 취소하고, 취소까지 처리되길 기다린다.
	private void cancel(OrderResponse order) {
		awaitProcessed(symbol);
		orderService.cancel(suspect.userId(), order.orderId());
		awaitProcessed(symbol);
	}

	// 역할을 가진 사용자로 로그인한 세션을 만든다.
	private MockHttpSession session(Role role) throws Exception {
		MockHttpSession session = new MockHttpSession();
		mockMvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
			.param("loginId", user(role).getLoginId()).param("password", PASSWORD)).andExpect(status().isOk());
		return session;
	}
}
