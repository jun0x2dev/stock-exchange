package com.exchange.global.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.Assert;

import com.zaxxer.hikari.HikariDataSource;

/**
 * DB 계정을 나눈다. 스키마 변경은 소유자 계정으로, 애플리케이션은 권한이 제한된 계정으로 접속한다.
 *
 * <p>접속 정보(주소, 소유자 계정)는 Docker Compose나 설정에서 온다.
 */
@Configuration
public class DataSourceConfig {

	/** 애플리케이션이 쓰는 연결. 원장을 고치거나 지울 권한이 없다. */
	@Bean
	@Primary
	HikariDataSource dataSource(ObjectProvider<JdbcConnectionDetails> details, DataSourceProperties properties,
			@Value("${exchange.db.app-password}") String password) {
		// afterMigrate.sql에 그대로 들어가므로 따옴표 같은 문자를 허용하지 않는다.
		Assert.isTrue(password.matches("[A-Za-z0-9_-]{16,}"),
			"EXCHANGE_DB_APP_PASSWORD는 영문, 숫자, _, -로 16자 이상이어야 한다");
		JdbcConnectionDetails found = details.getIfAvailable();
		return DataSourceBuilder.create().type(HikariDataSource.class)
			.url(found == null ? properties.determineUrl() : found.getJdbcUrl())
			.username("exchange_app").password(password).build();
	}

	/** 마이그레이션에만 쓰는 소유자 연결. 마이그레이션을 따로 실행하면 만들지 않는다. 헬스 체크도 이 연결을 쓰기 때문이다. */
	@Bean(defaultCandidate = false)
	@FlywayDataSource
	@ConditionalOnBooleanProperty(name = "spring.flyway.enabled", matchIfMissing = true)
	DataSource ownerDataSource(ObjectProvider<JdbcConnectionDetails> details, DataSourceProperties properties) {
		JdbcConnectionDetails found = details.getIfAvailable();
		return found == null
			? new DriverManagerDataSource(properties.determineUrl(), properties.determineUsername(),
				properties.determinePassword())
			: new DriverManagerDataSource(found.getJdbcUrl(), found.getUsername(), found.getPassword());
	}
}
