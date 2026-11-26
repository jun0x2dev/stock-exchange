package com.exchange.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.exchange.IntegrationTest;
import com.exchange.engine.Side;

/** 애플리케이션이 탈취돼 임의의 SQL을 실행하는 상황에서, 애플리케이션의 DB 계정이 할 수 없는 일을 검증한다. */
class DatabaseRoleTest extends IntegrationTest {

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate app;

	// 애플리케이션과 같은 연결을 쓴다. 대상 행이 있어야 트리거가 아니라 권한에 막히는지 알 수 있다.
	@BeforeEach
	void connect() {
		app = new JdbcTemplate(dataSource);
		String symbol = stock();
		Trader buyer = trader(1_000_000);
		Trader seller = trader(0);
		giveStock(seller, symbol, 1);
		place(seller, symbol, Side.SELL, 71_000, 1);
		place(buyer, symbol, Side.BUY, 71_000, 1);
		awaitProcessed(symbol);
	}

	@Test
	@DisplayName("애플리케이션은 관리자도 소유자도 아닌 계정으로 접속한다")
	void connectsAsRestrictedRole() {
		assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo("exchange_app");
		assertThat(app.queryForObject("""
			SELECT count(*) FROM pg_roles WHERE rolname = 'exchange_app'
			   AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication)
			""", Integer.class)).isZero();
		assertThat(app.queryForObject("SELECT count(*) FROM pg_class WHERE relowner = 'exchange_app'::regrole",
			Integer.class)).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"UPDATE cash_ledger SET cash_delta = 0", "DELETE FROM cash_ledger", "TRUNCATE cash_ledger",
		"UPDATE position_ledger SET quantity_delta = 0", "DELETE FROM position_ledger",
		"UPDATE execution SET price = 1", "DELETE FROM execution",
		"UPDATE audit_log SET user_id = 1", "DELETE FROM audit_log",
		"UPDATE audit_chain SET hash = ''", "DELETE FROM audit_chain",
		"UPDATE deposit SET amount = 1", "DELETE FROM deposit",
		"UPDATE orders SET price = 1", "UPDATE orders SET account_id = 1", "DELETE FROM orders",
		"UPDATE account SET user_id = 1", "DELETE FROM account",
		"UPDATE users SET role = 'APPROVER'", "UPDATE users SET password_hash = ''",
		"UPDATE stock SET base_price = 1", "INSERT INTO stock (symbol, name, base_price) VALUES ('HACK', 'HACK', 1)",
		"UPDATE ledger_adjustment SET requested_by = 1", "DELETE FROM ledger_adjustment",
		"UPDATE reconciliation_run SET status = 'PASSED'", "DELETE FROM reconciliation_run",
		"DELETE FROM reconciliation_violation",
		"ALTER TABLE cash_ledger DISABLE TRIGGER append_only", "DROP TABLE cash_ledger",
		"SET session_replication_role = replica", "CREATE TABLE hack (id INT)", "SET ROLE exchange" })
	@DisplayName("원장, 체결, 감사 로그, 권한은 고칠 수 없다")
	void cannot(String sql) {
		assertThatThrownBy(() -> app.execute(sql)).rootCause()
			.message().containsAnyOf("permission denied", "must be owner", "does not exist");
	}

	@Test
	@DisplayName("지울 수 있는 테이블은 감사 로그 대기 목록뿐이고, 비울 수 있는 테이블은 없다")
	void deletableTables() {
		assertThat(jdbc.queryForList("""
			SELECT table_name || ' ' || privilege_type FROM information_schema.role_table_grants
			 WHERE grantee = 'exchange_app' AND privilege_type NOT IN ('SELECT', 'INSERT')
			""", String.class)).containsExactly("audit_pending DELETE");
	}

	@Test
	@DisplayName("조회용 역할은 비밀번호 해시를 볼 수 없고 아무것도 바꿀 수 없다")
	void readonlyRole() {
		assertThat(jdbc.queryForList("""
			SELECT column_name FROM information_schema.column_privileges
			 WHERE grantee = 'exchange_readonly' AND table_name = 'users'
			""", String.class)).containsExactlyInAnyOrder("user_id", "login_id", "role");
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM information_schema.column_privileges
			 WHERE grantee = 'exchange_readonly' AND privilege_type <> 'SELECT'
			""", Integer.class)).isZero();
	}
}
