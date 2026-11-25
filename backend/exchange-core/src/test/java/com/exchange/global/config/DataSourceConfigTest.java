package com.exchange.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 실행 방식에 따라 만들어지는 DB 연결을 검증한다. */
class DataSourceConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(DataSourceConfig.class, Properties.class)
		.withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost/none");

	/** 접속 정보 설정을 읽는다. */
	@EnableConfigurationProperties(DataSourceProperties.class)
	static class Properties {
	}

	@Test
	@DisplayName("마이그레이션을 따로 실행하면 소유자 연결을 만들지 않는다")
	void noOwnerConnection() {
		runner.withPropertyValues("exchange.db.app-password=test-test-test-test", "spring.flyway.enabled=false")
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("ownerDataSource"));
		runner.withPropertyValues("exchange.db.app-password=test-test-test-test")
			.run(context -> assertThat(context).hasNotFailed().hasBean("ownerDataSource"));
	}

	@Test
	@DisplayName("앱 계정의 비밀번호가 없거나 짧거나 SQL에 쓸 수 없는 문자가 있으면 기동하지 않는다")
	void rejectsUnsafePassword() {
		for (String password : new String[] { "", "short", "test-test-test-test'" }) {
			runner.withPropertyValues("exchange.db.app-password=" + password)
				.run(context -> assertThat(context).hasFailed());
		}
	}
}
