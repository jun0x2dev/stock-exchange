package com.exchange.domain.outbox.service.impl;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.exchange.domain.outbox.repository.OutboxRepository;
import com.exchange.domain.outbox.service.OutboxAppended;
import com.exchange.engine.EngineCommand;
import com.exchange.global.config.KafkaConfig;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 저장된 명령을 순서대로 Kafka에 옮긴다.
 *
 * <p>한 묶음을 Kafka 트랜잭션 하나로 보낸다. 일부만 나간 뒤 다시 보내면 같은 주문의 취소가 접수보다 먼저 갈 수
 * 있기 때문이다. 보낸 뒤 표시하기 전에 죽으면 묶음 전체가 같은 순서로 다시 나가고, 엔진이 명령 ID로 버린다.
 */
@Slf4j
@Component
public class OutboxRelay {

	private static final int BATCH = 200;
	private static final long POLL_MILLIS = 500;

	private final OutboxRepository outboxRepository;
	private final TransactionTemplate transaction;
	private final ProducerFactory<?, ?> producerFactory;
	private final JsonMapper jsonMapper;
	private final AtomicBoolean scheduled = new AtomicBoolean();
	private final ScheduledExecutorService worker =
		Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "outbox-relay"));
	private Producer<String, String> producer;

	/** 발행에 필요한 저장소와 Kafka 설정을 받는다. */
	public OutboxRelay(OutboxRepository outboxRepository, TransactionTemplate transaction,
			ProducerFactory<?, ?> producerFactory, JsonMapper jsonMapper) {
		this.outboxRepository = outboxRepository;
		this.transaction = transaction;
		this.producerFactory = producerFactory;
		this.jsonMapper = jsonMapper;
	}

	/** 기동하면 남아 있는 명령부터 보내고, 깨우지 못한 명령에 대비해 주기적으로 확인한다. */
	@EventListener(ApplicationReadyEvent.class)
	void start() {
		worker.scheduleWithFixedDelay(this::relay, 0, POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	/** 명령이 커밋되면 바로 보낸다. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAppended(OutboxAppended event) {
		// 대기 작업은 하나면 충분하다. 실행될 때 쌓인 명령을 모두 읽는다.
		if (scheduled.compareAndSet(false, true)) {
			worker.execute(this::relay);
		}
	}

	/** 보내던 묶음을 마치고 멈춘다. */
	@PreDestroy
	void stop() throws InterruptedException {
		worker.shutdownNow();
		worker.awaitTermination(15, TimeUnit.SECONDS);
		closeProducer();
	}

	// 쌓인 명령을 모두 보낸다. 실패하면 다음 주기에 다시 보낸다.
	private void relay() {
		scheduled.set(false);
		try {
			connect();
			while (Boolean.TRUE.equals(transaction.execute(status -> relayBatch()))) {
				// 한 묶음을 꽉 채웠으면 더 남아 있을 수 있다.
			}
		} catch (RuntimeException e) {
			log.error("명령 발행 실패, 다시 시도한다", e);
			closeProducer();
		}
	}

	// 한 묶음을 보내고 표시한다. 더 남았을 수 있으면 true를 반환한다.
	private boolean relayBatch() {
		if (!outboxRepository.tryLock()) {
			return false;
		}
		List<EngineCommand> commands = outboxRepository.findUnpublished(Limit.of(BATCH));
		if (commands.isEmpty()) {
			return false;
		}
		outboxRepository.skipDiskSync();
		producer.beginTransaction();
		for (EngineCommand command : commands) {
			producer.send(new ProducerRecord<>(KafkaConfig.COMMANDS, command.symbol(),
				jsonMapper.writeValueAsString(command)));
		}
		// 하나라도 실패했으면 여기서 예외가 나고 묶음 전체가 취소된다.
		producer.commitTransaction();
		outboxRepository.markPublished(commands.stream().map(EngineCommand::commandId).toList());
		return commands.size() == BATCH;
	}

	// DB 트랜잭션을 열기 전에 연결해, Kafka가 없을 때 DB 연결을 붙잡지 않는다.
	private void connect() {
		if (producer != null) {
			return;
		}
		Map<String, Object> config = new HashMap<>(producerFactory.getConfigurationProperties());
		config.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "exchange-core-outbox");
		config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
		config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
		config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
		KafkaProducer<String, String> created =
			new KafkaProducer<>(config, new StringSerializer(), new StringSerializer());
		try {
			created.initTransactions();
		} catch (RuntimeException e) {
			created.close(Duration.ZERO);
			throw e;
		}
		producer = created;
	}

	// 끝내지 못한 트랜잭션은 다음 연결의 initTransactions가 취소한다.
	private void closeProducer() {
		if (producer != null) {
			producer.close(Duration.ZERO);
			producer = null;
		}
	}
}
