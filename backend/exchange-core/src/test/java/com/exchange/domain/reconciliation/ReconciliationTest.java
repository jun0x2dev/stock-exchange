package com.exchange.domain.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.exchange.IntegrationTest;
import com.exchange.domain.reconciliation.dto.ReconciliationDetail;
import com.exchange.domain.reconciliation.dto.ReconciliationResponse;
import com.exchange.domain.reconciliation.entity.ReconciliationStatus;
import com.exchange.domain.reconciliation.service.ReconciliationService;
import com.exchange.domain.user.entity.Role;
import com.exchange.global.error.BusinessException;
import com.exchange.global.error.ErrorCode;

/** 대사가 조작을 찾아내는지, 정기 대사가 날짜마다 한 번만 기록되는지 검증한다. 조작은 끝에 되돌린다. */
class ReconciliationTest extends IntegrationTest {

	@Autowired
	private ReconciliationService reconciliationService;

	@Test
	@DisplayName("잔고와 원장이 맞으면 통과하고 실행한 사람과 시각을 남긴다")
	void passes() {
		long operator = user(Role.OPERATOR).getUserId();
		trader(1_000_000);
		ReconciliationResponse run = reconciliationService.run(operator);
		assertThat(run.status()).isEqualTo(ReconciliationStatus.PASSED);
		assertThat(run.auditChecked()).isPositive();
		assertThat(jdbc.queryForObject("SELECT requested_by FROM reconciliation_run WHERE run_id = ?", Long.class,
			run.runId())).isEqualTo(operator);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'RECONCILE' AND target = ?",
			Integer.class, "reconciliation=" + run.runId())).isOne();
	}

	@Test
	@DisplayName("DB에서 잔고를 직접 바꾸면 원장과 어긋난 계좌를 찾는다")
	void detectsBalanceTampering() {
		Trader trader = trader(1_000_000);
		jdbc.update("UPDATE account SET cash_balance = cash_balance + 1 WHERE account_id = ?", trader.accountId());
		try {
			ReconciliationDetail detail = detail(reconciliationService.run(user(Role.AUDITOR).getUserId()));
			assertThat(detail.run().status()).isEqualTo(ReconciliationStatus.FAILED);
			assertThat(detail.run().invariantViolations()).isOne();
			assertThat(detail.violations()).extracting(ReconciliationDetail.Violation::kind,
				ReconciliationDetail.Violation::detail).containsExactly(
					org.assertj.core.groups.Tuple.tuple("I2", "account=" + trader.accountId()));
		} finally {
			jdbc.update("UPDATE account SET cash_balance = cash_balance - 1 WHERE account_id = ?", trader.accountId());
		}
		assertThat(reconciliationService.run(user(Role.AUDITOR).getUserId()).status())
			.isEqualTo(ReconciliationStatus.PASSED);
	}

	@Test
	@DisplayName("감사 로그가 고쳐졌으면 잔고가 맞아도 실패로 남긴다")
	void detectsAuditTampering() {
		trader(1_000);
		await(() -> jdbc.queryForObject("SELECT count(*) FROM audit_pending", Integer.class) == 0);
		long auditId = jdbc.queryForObject("SELECT max(audit_id) FROM audit_chain", Long.class);
		String original = jdbc.queryForObject("SELECT result FROM audit_log WHERE audit_id = ?", String.class, auditId);
		tamper("UPDATE audit_log SET result = 'FORGED' WHERE audit_id = " + auditId);
		try {
			ReconciliationDetail detail = detail(reconciliationService.run(user(Role.AUDITOR).getUserId()));
			assertThat(detail.run().status()).isEqualTo(ReconciliationStatus.FAILED);
			assertThat(detail.run().invariantViolations()).isZero();
			assertThat(detail.violations()).extracting(ReconciliationDetail.Violation::kind).containsExactly("AUDIT");
		} finally {
			tamper("UPDATE audit_log SET result = '" + original + "' WHERE audit_id = " + auditId);
		}
	}

	@Test
	@DisplayName("정기 대사는 여러 번, 동시에 실행해도 날짜마다 한 번만 기록된다")
	void scheduledOncePerDay() throws Exception {
		ConcurrentLinkedQueue<Optional<ReconciliationResponse>> results = new ConcurrentLinkedQueue<>();
		List<Runnable> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			tasks.add(() -> results.add(reconciliationService.runScheduled()));
		}
		assertThat(runConcurrently(tasks)).isEqualTo(8);
		reconciliationService.runScheduled();
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM reconciliation_run WHERE trigger = 'SCHEDULED'
			   AND business_date = CAST(timezone('Asia/Seoul', now()) AS date) - 1
			""", Integer.class)).isOne();
		// 기동한 지 1분이 지나 작업자가 먼저 기록했을 수도 있다.
		assertThat(results.stream().filter(Optional::isPresent).count()).isLessThanOrEqualTo(1);
	}

	@Test
	@DisplayName("수동 대사가 겹치면 하나만 실행하고 나머지는 진행 중이라고 알린다")
	void manualRunsDoNotOverlap() throws Exception {
		long operator = user(Role.OPERATOR).getUserId();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		List<Runnable> tasks = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			tasks.add(() -> {
				try {
					reconciliationService.run(operator);
				} catch (BusinessException e) {
					errors.add(e);
					throw e;
				}
			});
		}
		assertThat(runConcurrently(tasks)).isPositive();
		assertThat(errors).allSatisfy(e ->
			assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RECONCILIATION_RUNNING));
	}

	@Test
	@DisplayName("대사 기록은 고치거나 지울 수 없다")
	void historyIsAppendOnly() {
		long runId = reconciliationService.run(user(Role.OPERATOR).getUserId()).runId();
		for (String sql : List.of("UPDATE reconciliation_run SET status = 'PASSED' WHERE run_id = " + runId,
			"DELETE FROM reconciliation_run WHERE run_id = " + runId, "DELETE FROM reconciliation_violation")) {
			assertThatThrownBy(() -> jdbc.execute(sql)).hasMessageContaining("append-only");
		}
	}

	@Test
	@DisplayName("직원만 대사 결과를 보고, 운영자와 감사 담당자만 실행한다")
	void roles() throws Exception {
		MockHttpSession customer = session(Role.CUSTOMER);
		MockHttpSession approver = session(Role.APPROVER);
		MockHttpSession operator = session(Role.OPERATOR);
		MockHttpSession auditor = session(Role.AUDITOR);
		String path = "/api/v1/admin/reconciliations";
		perform(get(path), customer).andExpect(status().isForbidden());
		perform(post(path), customer).andExpect(status().isForbidden());
		perform(post(path), approver).andExpect(status().isForbidden());
		perform(post(path), operator).andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PASSED"));
		String runId = jdbc.queryForObject("SELECT max(run_id) FROM reconciliation_run", String.class);
		perform(post(path), auditor).andExpect(status().isCreated());
		perform(get(path + "?size=2"), approver).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2));
		perform(get(path + "/" + runId), approver).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.violations").isEmpty());
		perform(get(path + "/" + runId), customer).andExpect(status().isForbidden());
		perform(get(path + "/999999999"), auditor).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RECONCILIATION_NOT_FOUND"));
	}

	// 결과와 불일치 내역을 읽는다.
	private ReconciliationDetail detail(ReconciliationResponse run) {
		return reconciliationService.get(run.runId());
	}

	// 트리거를 끄고 실행한다. DB 관리자만 할 수 있다.
	private void tamper(String sql) {
		jdbc.execute((ConnectionCallback<Void>) connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("SET session_replication_role = replica");
				statement.execute(sql);
				statement.execute("SET session_replication_role = origin");
			}
			return null;
		});
	}

	// 세션으로 요청한다.
	private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request,
			MockHttpSession session) throws Exception {
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
