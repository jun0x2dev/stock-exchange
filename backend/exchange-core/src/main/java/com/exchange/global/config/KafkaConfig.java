package com.exchange.global.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Kafka 토픽과 소비 실패 처리 설정. */
@Configuration
public class KafkaConfig {

	/** 엔진에 보내는 명령. 키는 종목이다. */
	public static final String COMMANDS = "order-command";

	/** 엔진의 처리 결과. 키는 종목이다. */
	public static final String RESULTS = "engine-event";

	private static final int PARTITIONS = 3;

	/** 엔진이 처음부터 다시 읽어 호가창을 되살리므로 지우지 않는다. */
	@Bean
	NewTopic commandTopic() {
		return topic(COMMANDS);
	}

	/** 멈춘 종목을 다시 열 때 반영하지 못한 결과부터 다시 읽으므로 지우지 않는다. */
	@Bean
	NewTopic resultTopic() {
		return topic(RESULTS);
	}

	/** 정해진 횟수를 넘겼다고 이벤트를 건너뛰면 잔고가 틀어지므로 성공할 때까지 다시 시도한다. */
	@Bean
	CommonErrorHandler kafkaErrorHandler() {
		return new DefaultErrorHandler(new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
	}

	// 보존 기간이 없는 토픽을 만든다.
	private static NewTopic topic(String name) {
		return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1)
			.config(TopicConfig.RETENTION_MS_CONFIG, "-1")
			.config(TopicConfig.RETENTION_BYTES_CONFIG, "-1")
			.build();
	}
}
