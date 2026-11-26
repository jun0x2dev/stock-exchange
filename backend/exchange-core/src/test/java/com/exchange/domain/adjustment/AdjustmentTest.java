package com.exchange.domain.adjustment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.exchange.IntegrationTest;
import com.exchange.domain.adjustment.dto.AdjustmentRequest;
import com.exchange.domain.adjustment.entity.AdjustmentStatus;
import com.exchange.domain.adjustment.service.AdjustmentService;
import com.exchange.domain.user.entity.Role;
import com.exchange.engine.Side;
import com.exchange.global.error.BusinessException;
import com.exchange.global.error.ErrorCode;

/** 원장 정정의 요청·승인 분리와, 정정해도 불변식이 유지되는지 검증한다. */
class AdjustmentTest extends IntegrationTest {

	@Autowired
	private AdjustmentService adjustmentService;

	private long operator;
	private long approver;

	// 요청자와 승인자를 만든다.
	@BeforeEach
	void users() {
		operator = user(Role.OPERATOR).getUserId();
		approver = user(Role.APPROVER).getUserId();
	}

	// 어떤 경우에도 불변식이 깨지지 않아야 한다.
	@AfterEach
	void invariants() {
		assertInvariants();
	}

	@Test
	@DisplayName("승인하면 반대 전표로 되돌리고, 원래 기록은 그대로 남는다")
	void reversesCash() {
		Trader trader = trader(1_000_000);
		long id = request(depositOf(trader));
		assertThat(cash(trader)).isEqualTo(1_000_000);
		assertThat(adjustmentService.approve(approver, id).status()).isEqualTo(AdjustmentStatus.APPROVED);
		assertThat(cash(trader)).isZero();
		assertThat(jdbc.queryForList("SELECT type || ' ' || cash_delta FROM cash_ledger WHERE account_id = ? ORDER BY ledger_id",
			String.class, trader.accountId())).containsExactly("DEPOSIT 1000000", "REVERSAL -1000000");
		assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE target = ? ORDER BY audit_id", String.class,
			"adjustment=" + id)).containsExactly("REQUEST_ADJUSTMENT", "APPROVE_ADJUSTMENT");
	}

	@Test
	@DisplayName("잘못 입고한 주식을 되돌리면 남은 주식의 원가만 남는다")
	void reversesStock() {
		Trader trader = trader(0);
		String symbol = stock();
		giveStock(trader, symbol, 10);
		giveStock(trader, symbol, 3);
		adjustmentService.approve(approver, request(depositOf(trader)));
		assertThat(jdbc.queryForObject("SELECT quantity || ' ' || total_cost FROM position WHERE account_id = ?",
			String.class, trader.accountId())).isEqualTo("10 710000");
		long first = jdbc.queryForObject("SELECT min(deposit_id) FROM deposit WHERE account_id = ?", Long.class,
			trader.accountId());
		adjustmentService.approve(approver, request(first));
		assertThat(jdbc.queryForObject("SELECT quantity || ' ' || total_cost FROM position WHERE account_id = ?",
			String.class, trader.accountId())).isEqualTo("0 0");
	}

	@Test
	@DisplayName("요청한 사람은 승인할 수 없고, 서비스를 거치지 않아도 DB가 막는다")
	void cannotApproveOwnRequest() {
		Trader trader = trader(1_000);
		long id = request(depositOf(trader));
		assertFails(() -> adjustmentService.approve(operator, id), ErrorCode.ADJUSTMENT_SELF_DECISION);
		assertFails(() -> adjustmentService.reject(operator, id), ErrorCode.ADJUSTMENT_SELF_DECISION);
		assertThatThrownBy(() -> jdbc.update("""
			UPDATE ledger_adjustment SET status = 'APPROVED', decided_by = requested_by, decided_at = now()
			 WHERE adjustment_id = ?
			""", id)).rootCause().hasMessageContaining("ledger_adjustment_check");
		assertThat(cash(trader)).isEqualTo(1_000);
	}

	@Test
	@DisplayName("이미 쓴 돈이나 주문에 묶인 돈은 되돌릴 수 없고, 요청은 대기 상태로 남는다")
	void cannotReverseReserved() {
		Trader trader = trader(100_000);
		long id = request(depositOf(trader));
		place(trader, stock(), Side.BUY, 71_000, 1);
		assertFails(() -> adjustmentService.approve(approver, id), ErrorCode.ADJUSTMENT_NOT_REVERSIBLE);
		assertThat(jdbc.queryForObject("SELECT status FROM ledger_adjustment WHERE adjustment_id = ?", String.class, id))
			.isEqualTo("REQUESTED");
		assertThat(cash(trader)).isEqualTo(100_000);
	}

	@Test
	@DisplayName("매도 주문에 묶인 주식은 되돌릴 수 없다")
	void cannotReverseReservedStock() {
		Trader trader = trader(0);
		String symbol = stock();
		giveStock(trader, symbol, 2);
		long id = request(depositOf(trader));
		place(trader, symbol, Side.SELL, 71_000, 1);
		assertFails(() -> adjustmentService.approve(approver, id), ErrorCode.ADJUSTMENT_NOT_REVERSIBLE);
	}

	@Test
	@DisplayName("같은 전표는 동시에 요청해도 한 번만 접수되고, 거절되면 다시 요청할 수 있다")
	void oneRequestPerDeposit() throws Exception {
		long depositId = depositOf(trader(1_000));
		List<Runnable> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(() -> request(depositId));
		}
		assertThat(runConcurrently(tasks)).isEqualTo(1);
		long id = jdbc.queryForObject("SELECT adjustment_id FROM ledger_adjustment WHERE deposit_id = ?", Long.class, depositId);
		adjustmentService.reject(approver, id);
		long again = request(depositId);
		adjustmentService.approve(approver, again);
		assertFails(() -> request(depositId), ErrorCode.ADJUSTMENT_ALREADY_REQUESTED);
		assertFails(() -> adjustmentService.approve(approver, id), ErrorCode.ADJUSTMENT_ALREADY_DECIDED);
	}

	@Test
	@DisplayName("동시에 승인해도 한 번만 되돌린다")
	void concurrentApproval() throws Exception {
		Trader trader = trader(1_000);
		accountService.deposit(trader.userId(), trader.accountId(), key(), new com.exchange.domain.account.dto.DepositRequest(1_000L));
		long id = request(depositOf(trader));
		List<Runnable> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(() -> adjustmentService.approve(approver, id));
		}
		assertThat(runConcurrently(tasks)).isEqualTo(1);
		assertThat(cash(trader)).isEqualTo(1_000);
	}

	@Test
	@DisplayName("승인과 주문이 겹쳐도 같은 돈을 두 번 쓰지 않는다")
	void approvalRacesWithOrder() throws Exception {
		String symbol = stock();
		for (int i = 0; i < 10; i++) {
			Trader trader = trader(71_000);
			long id = request(depositOf(trader));
			assertThat(runConcurrently(List.of(() -> adjustmentService.approve(approver, id),
				() -> place(trader, symbol, Side.BUY, 71_000, 1)))).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("역할마다 정해진 일만 할 수 있고, 막힌 시도는 감사 로그에 남는다")
	void rolesAreSeparated() throws Exception {
		long depositId = depositOf(trader(1_000));
		MockHttpSession customer = session(Role.CUSTOMER);
		MockHttpSession operatorSession = session(Role.OPERATOR);
		MockHttpSession approverSession = session(Role.APPROVER);
		MockHttpSession auditor = session(Role.AUDITOR);
		for (MockHttpSession denied : List.of(customer, approverSession, auditor)) {
			perform(create(depositId), denied).andExpect(status().isForbidden());
		}
		perform(create(depositId), operatorSession).andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.status").value("REQUESTED"));
		long id = jdbc.queryForObject("SELECT adjustment_id FROM ledger_adjustment WHERE deposit_id = ?", Long.class, depositId);
		String path = "/api/v1/admin/adjustments/" + id;
		for (MockHttpSession denied : List.of(customer, operatorSession, auditor)) {
			perform(post(path + "/approval"), denied).andExpect(status().isForbidden());
			perform(post(path + "/rejection"), denied).andExpect(status().isForbidden());
		}
		perform(get("/api/v1/admin/adjustments"), customer).andExpect(status().isForbidden());
		for (MockHttpSession denied : List.of(customer, approverSession, auditor)) {
			perform(get("/api/v1/admin/deposits"), denied).andExpect(status().isForbidden());
		}
		perform(get("/api/v1/admin/deposits?size=1"), operatorSession).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].depositId").value(depositId))
			.andExpect(jsonPath("$.data.items[0].owner").exists());
		for (MockHttpSession denied : List.of(customer, operatorSession, approverSession)) {
			perform(get("/api/v1/admin/audit/verification"), denied).andExpect(status().isForbidden());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE result = 'FORBIDDEN' AND action = ?",
			Integer.class, "POST " + path + "/approval")).isEqualTo(3);

		perform(get("/api/v1/admin/adjustments"), auditor).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].adjustmentId").value(id))
			.andExpect(jsonPath("$.data.items[0].requester").exists())
			.andExpect(jsonPath("$.data.items[0].decider").doesNotExist());
		perform(get("/api/v1/admin/audit/verification"), auditor).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.valid").value(true));
		perform(post(path + "/approval"), approverSession).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("APPROVED"));
	}

	@Test
	@DisplayName("없는 전표, 없는 요청, 빈 사유는 거부한다")
	void invalidRequests() throws Exception {
		assertFails(() -> request(999_999_999), ErrorCode.DEPOSIT_NOT_FOUND);
		assertFails(() -> adjustmentService.approve(approver, 999_999_999), ErrorCode.ADJUSTMENT_NOT_FOUND);
		perform(post("/api/v1/admin/adjustments").contentType(MediaType.APPLICATION_JSON)
			.content("{\"depositId\":1,\"reason\":\" \"}"), session(Role.OPERATOR)).andExpect(status().isBadRequest());
	}

	// 정정을 요청한다.
	private long request(long depositId) {
		return adjustmentService.request(operator, new AdjustmentRequest(depositId, "잘못된 입금")).adjustmentId();
	}

	// 계좌의 마지막 입금·입고.
	private long depositOf(Trader trader) {
		return jdbc.queryForObject("SELECT max(deposit_id) FROM deposit WHERE account_id = ?", Long.class, trader.accountId());
	}

	// 계좌의 예수금.
	private long cash(Trader trader) {
		return accountService.get(trader.userId(), trader.accountId()).cashBalance();
	}

	// 정해진 에러 코드로 실패하는지 확인한다.
	private static void assertFails(ThrowingCallable call, ErrorCode code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(code));
	}

	// 정정 요청을 만든다.
	private static MockHttpServletRequestBuilder create(long depositId) {
		return post("/api/v1/admin/adjustments").contentType(MediaType.APPLICATION_JSON)
			.content("{\"depositId\":%d,\"reason\":\"잘못된 입금\"}".formatted(depositId));
	}

	// 세션으로 요청한다.
	private ResultActions perform(MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
		return mockMvc.perform(request.session(session).with(csrf()));
	}

	// 역할을 가진 사용자로 로그인한 세션을 만든다.
	private MockHttpSession session(Role role) throws Exception {
		MockHttpSession session = new MockHttpSession();
		mockMvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
			.param("loginId", user(role).getLoginId()).param("password", PASSWORD)).andExpect(status().isOk());
		return session;
	}
}
