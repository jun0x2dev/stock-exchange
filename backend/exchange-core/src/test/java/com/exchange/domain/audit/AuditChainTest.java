package com.exchange.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;

import com.exchange.IntegrationTest;
import com.exchange.domain.audit.dto.AuditVerification;
import com.exchange.domain.audit.service.AuditChainService;
import com.exchange.domain.audit.service.AuditService;

/** 트리거까지 끌 수 있는 DB 관리자가 감사 로그를 고치는 공격을 재현한다. 원장처럼 되돌릴 수 없으므로 끝에 원래 값으로 되돌린다. */
class AuditChainTest extends IntegrationTest {

	@Autowired
	private AuditChainService chainService;

	@Autowired
	private AuditService auditService;

	@Value("${exchange.audit.anchor-file}")
	private String anchorFile;

	private long auditId;

	// 감사 로그 세 건을 남기고 체인에 들어갈 때까지 기다린다.
	@BeforeEach
	void record() {
		for (int i = 0; i < 3; i++) {
			auditService.recordSuccess("TEST", "target=" + i);
		}
		awaitChained();
		auditId = jdbc.queryForObject("SELECT max(audit_id) - 1 FROM audit_log", Long.class);
		assertValid();
	}

	@Test
	@DisplayName("내용을 고치면 어느 로그가 바뀌었는지 찾는다")
	void detectsModification() {
		tamper("UPDATE audit_log SET user_id = 999999 WHERE audit_id = " + auditId);
		assertThat(chainService.verify().problems()).containsExactly("감사 로그 " + auditId + "의 내용이 기록 당시와 다름");
		tamper("UPDATE audit_log SET user_id = NULL WHERE audit_id = " + auditId);
		assertValid();
	}

	@Test
	@DisplayName("값을 지우거나 옆 컬럼으로 옮겨도 찾는다")
	void detectsMovedValue() {
		tamper("UPDATE audit_log SET action = 'TESTtarget=1', target = NULL WHERE audit_id = " + auditId);
		assertThat(chainService.verify().valid()).isFalse();
		tamper("UPDATE audit_log SET action = 'TEST', target = 'target=1' WHERE audit_id = " + auditId);
		assertValid();
	}

	@Test
	@DisplayName("로그만 지우거나 체인까지 함께 지워도 찾는다")
	void detectsDeletion() {
		backup();
		tamper("DELETE FROM audit_log WHERE audit_id = " + auditId);
		assertThat(chainService.verify().problems()).containsExactly("감사 로그 " + auditId + "이 지워짐");
		tamper("DELETE FROM audit_chain WHERE audit_id = " + auditId);
		// 체인 번호가 비고, 다음 칸이 앞과 이어지지 않는다.
		assertThat(chainService.verify().problems()).hasSize(2);
		restore();
		assertValid();
	}

	@Test
	@DisplayName("번호를 당겨 빈자리를 감춰도 앞 칸과 이어지지 않아 찾는다")
	void detectsRenumbering() {
		backup();
		long seq = jdbc.queryForObject("SELECT chain_seq FROM audit_chain WHERE audit_id = ?", Long.class, auditId);
		tamper("DELETE FROM audit_chain WHERE chain_seq = " + seq,
			"UPDATE audit_chain SET chain_seq = chain_seq - 1 WHERE chain_seq = " + (seq + 1));
		assertThat(chainService.verify().problems()).contains("체인 " + seq + "이 앞과 이어지지 않음");
		restore();
		assertValid();
	}

	@Test
	@DisplayName("뒤쪽을 통째로 잘라 내도 DB 밖에 남긴 값과 달라 찾는다")
	void detectsTruncation() {
		chainService.anchor();
		backup();
		long seq = jdbc.queryForObject("SELECT max(chain_seq) FROM audit_chain", Long.class);
		tamper("DELETE FROM audit_log WHERE audit_id >= " + auditId, "DELETE FROM audit_chain WHERE audit_id >= " + auditId);
		assertThat(chainService.verify().problems()).containsExactly("DB 밖에 남긴 체인 " + seq + "이 지워짐");
		restore();
		assertValid();
	}

	@Test
	@DisplayName("대기 목록에서 빼 체인에 넣지 않은 로그도 찾는다")
	void detectsUnchained() {
		tamper("INSERT INTO audit_log (audit_id, action, result, created_at) VALUES (0, 'TEST', 'SUCCESS', now() - interval '1 hour')");
		assertThat(chainService.verify().problems()).containsExactly("체인에 들어가지 않은 감사 로그 1건");
		tamper("DELETE FROM audit_log WHERE audit_id = 0");
		assertValid();
	}

	@Test
	@DisplayName("DB 밖에는 바뀐 것이 있을 때만 남긴다")
	void anchorsOnlyChanges() throws Exception {
		chainService.anchor();
		long size = java.nio.file.Files.size(java.nio.file.Path.of(anchorFile));
		chainService.anchor();
		assertThat(java.nio.file.Files.size(java.nio.file.Path.of(anchorFile))).isEqualTo(size);
	}

	// 모든 감사 로그가 체인에 들어갈 때까지 기다린다.
	private void awaitChained() {
		await(() -> jdbc.queryForObject("SELECT count(*) FROM audit_pending", Integer.class) == 0);
	}

	// 문제가 없는지 확인한다.
	private void assertValid() {
		AuditVerification result = chainService.verify();
		assertThat(result.problems()).isEmpty();
		assertThat(result.valid()).isTrue();
		assertThat(result.checked()).isPositive();
	}

	// 되돌릴 원본을 남긴다.
	private void backup() {
		for (String table : List.of("audit_log", "audit_chain")) {
			jdbc.execute("DROP TABLE IF EXISTS backup_" + table);
			jdbc.execute("CREATE TABLE backup_" + table + " AS TABLE " + table);
		}
	}

	// 원본으로 되돌린다.
	private void restore() {
		tamper("DELETE FROM audit_chain", "DELETE FROM audit_log", "INSERT INTO audit_log SELECT * FROM backup_audit_log",
			"INSERT INTO audit_chain SELECT * FROM backup_audit_chain");
	}

	// 트리거와 외래키를 끄고 실행한다. DB 관리자만 할 수 있다.
	private void tamper(String... statements) {
		jdbc.execute((ConnectionCallback<Void>) connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("SET session_replication_role = replica");
				for (String sql : statements) {
					statement.execute(sql);
				}
				statement.execute("SET session_replication_role = origin");
			}
			return null;
		});
	}
}
