package com.exchange.feed;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exchange.market.Quote;

import tools.jackson.databind.json.JsonMapper;

/**
 * 모의 시세를 1초마다 {@code quote} 토픽에 보낸다.
 *
 * <p>호가 봇은 이 토픽만 읽으므로, 실제 시세 수집기로 바꿔도 봇은 고치지 않는다.
 */
public final class FeedApplication {

	/** 참고 시세 토픽. 키는 종목이다. */
	public static final String TOPIC = "quote";

	private static final Logger log = LoggerFactory.getLogger(FeedApplication.class);

	// core의 가상 종목과 기준가.
	private static final String DEFAULT_STOCKS =
		"900010:71000,900020:182500,900030:45350,900040:236500,900050:18420,900060:4985";

	private FeedApplication() {
	}

	/** 환경 변수로 Kafka 주소, 종목, 시드를 받아 실행한다. */
	public static void main(String[] args) throws InterruptedException {
		long seed = Long.parseLong(System.getenv().getOrDefault("FEED_SEED", "20261125"));
		Map<String, PriceSimulator> stocks = new LinkedHashMap<>();
		for (String entry : System.getenv().getOrDefault("FEED_STOCKS", DEFAULT_STOCKS).split(",")) {
			String[] parts = entry.split(":");
			// 종목마다 다른 흐름이 나오도록 시드에 종목 코드를 섞는다.
			stocks.put(parts[0], new PriceSimulator(Long.parseLong(parts[1]), seed + parts[0].hashCode()));
		}
		JsonMapper mapper = JsonMapper.builder().build();
		try (KafkaProducer<String, String> producer = new KafkaProducer<>(config())) {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> producer.close(Duration.ofSeconds(1))));
			log.info("모의 시세 시작: {}종목, 시드 {}", stocks.size(), seed);
			while (true) {
				stocks.forEach((symbol, simulator) -> producer.send(
					new ProducerRecord<>(TOPIC, symbol, mapper.writeValueAsString(new Quote(symbol, simulator.next()))),
					(metadata, e) -> {
						if (e != null) {
							log.warn("시세 전송 실패: {}", e.toString());
						}
					}));
				TimeUnit.SECONDS.sleep(1);
			}
		}
	}

	// 오래된 시세는 쓸모가 없으므로 쌓아 두고 다시 보내지 않는다.
	private static Properties config() {
		Properties config = new Properties();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
			System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"));
		config.put(ProducerConfig.ACKS_CONFIG, "1");
		config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
		config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1_000);
		config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_000);
		config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2_000);
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return config;
	}
}
