package com.exchange;

import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.exchange.engine.app.EngineRunner;

/** 테스트용 PostgreSQL, Kafka, 매칭 엔진 설정. 로컬 개발 환경과 같은 버전을 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** 접속 정보가 자동으로 주입되는 PostgreSQL 컨테이너를 만든다. */
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// 테스트 데이터는 남기지 않으므로 메모리에 둔다. 느린 디스크에서 기동이 제한 시간을 넘기는 것을 막는다.
		return new PostgreSQLContainer("postgres:17-alpine").withTmpFs(Map.of("/var/lib/postgresql/data", "rw"));
	}

	/** 접속 정보가 자동으로 주입되는 Kafka 컨테이너를 만든다. */
	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer("apache/kafka:4.2.2").withEnv("KAFKA_HEAP_OPTS", "-Xms192m -Xmx256m")
			.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
			.withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", "1")
			.withEnv("KAFKA_TRANSACTION_STATE_LOG_NUM_PARTITIONS", "1")
			.withTmpFs(Map.of("/tmp/kafka-logs", "rw,uid=1000,gid=1000", "/tmp/kraft-combined-logs", "rw,uid=1000,gid=1000"));
	}

	/** 별도 프로세스 대신 같은 프로세스에서 실제 엔진을 띄운다. 테스트가 멈추고 다시 시작할 수 있다. */
	@Bean(initMethod = "start", destroyMethod = "close")
	EngineRunner engineRunner(KafkaContainer kafka) {
		return new EngineRunner(kafka.getBootstrapServers());
	}
}
