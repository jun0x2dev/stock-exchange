package com.exchange.engine.app;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.exchange.engine.BookSnapshot;
import com.exchange.engine.CommandProcessor;
import com.exchange.engine.EngineCommand;
import com.exchange.engine.EngineEvent;
import com.exchange.engine.EngineResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * 명령 토픽을 읽어 매칭하고 결과를 내보낸다. DB에 접근하지 않는다.
 *
 * <p>결과와 읽은 위치를 한 Kafka 트랜잭션으로 커밋한다. 시작할 때 명령을 처음부터 다시 처리해 호가창을 되살리되,
 * 이미 커밋한 위치까지는 결과를 내보내지 않는다. 그래서 어느 시점에 죽어도 결과가 빠지거나 두 번 나가지 않는다.
 */
public final class EngineRunner implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(EngineRunner.class);

	private static final String ID = "exchange-engine";
	private static final Duration POLL = Duration.ofMillis(200);
	private static final long RETRY_MILLIS = 2_000;

	private final String bootstrapServers;
	private final JsonMapper mapper = JsonMapper.builder().build();
	private Thread thread;
	private volatile boolean running;
	private volatile KafkaConsumer<String, String> consumer;
	private volatile CommandProcessor processor;
	private volatile CountDownLatch replayed = new CountDownLatch(1);

	/** 접속할 Kafka 주소로 만든다. */
	public EngineRunner(String bootstrapServers) {
		this.bootstrapServers = bootstrapServers;
	}

	/** 엔진 스레드를 시작한다. */
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		replayed = new CountDownLatch(1);
		thread = new Thread(this::runUntilStopped, "engine");
		thread.start();
	}

	/** 처리 중인 묶음을 마치고 멈춘다. */
	@Override
	public synchronized void close() {
		running = false;
		KafkaConsumer<String, String> current = consumer;
		if (current != null) {
			current.wakeup();
		}
		try {
			if (thread != null) {
				thread.join(TimeUnit.SECONDS.toMillis(15));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 명령 기록을 다시 처리해 호가창을 되살릴 때까지 기다린다. */
	public boolean awaitReplayed(Duration timeout) throws InterruptedException {
		return replayed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	/** 종목의 호가창. 엔진이 멈춰 있을 때만 쓴다. */
	public BookSnapshot snapshot(String symbol, int depth) {
		return processor.snapshot(symbol, depth);
	}

	/** 엔진 스레드가 끝날 때까지 기다린다. */
	public void join() throws InterruptedException {
		thread.join();
	}

	// 실패하면 메모리 상태를 믿지 않고 버린 뒤 처음부터 다시 처리한다.
	private void runUntilStopped() {
		while (running) {
			try {
				run();
			} catch (WakeupException | InterruptException e) {
				log.info("엔진 중지");
			} catch (RuntimeException e) {
				log.error("엔진 실패, 다시 시작한다", e);
				sleep();
			}
		}
	}

	// 한 번 연결해 멈추거나 실패할 때까지 처리한다.
	private void run() {
		CommandProcessor fresh = new CommandProcessor();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig());
				KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig())) {
			this.consumer = consumer;
			this.processor = fresh;
			// 같은 ID의 이전 엔진을 차단하고, 그 엔진이 끝내지 못한 트랜잭션을 취소한다.
			producer.initTransactions();
			List<TopicPartition> partitions = consumer.partitionsFor(EngineTopics.COMMANDS).stream()
				.map(p -> new TopicPartition(p.topic(), p.partition())).toList();
			if (partitions.isEmpty()) {
				throw new IllegalStateException("명령 토픽이 없다. core가 먼저 토픽을 만들어야 한다");
			}
			consumer.assign(partitions);
			Map<TopicPartition, Long> committed = committed(consumer, partitions);
			consumer.seekToBeginning(partitions);
			while (running) {
				process(consumer, producer, fresh, committed, consumer.poll(POLL));
				if (replayed.getCount() > 0
					&& partitions.stream().allMatch(p -> consumer.position(p) >= committed.get(p))) {
					log.info("호가창 복원 완료: {}", committed);
					replayed.countDown();
				}
			}
		} finally {
			this.consumer = null;
		}
	}

	// 묶음을 매칭하고, 새 명령이면 결과와 읽은 위치를 함께 커밋한다.
	private void process(KafkaConsumer<String, String> consumer, KafkaProducer<String, String> producer,
			CommandProcessor processor, Map<TopicPartition, Long> committed, ConsumerRecords<String, String> records) {
		Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
		for (ConsumerRecord<String, String> record : records) {
			EngineCommand command = mapper.readValue(record.value(), EngineCommand.class);
			List<EngineEvent> events = processor.process(command);
			TopicPartition partition = new TopicPartition(record.topic(), record.partition());
			if (record.offset() < committed.get(partition)) {
				// 이전에 결과까지 커밋한 명령이다. 호가창만 되살린다.
				continue;
			}
			if (offsets.isEmpty()) {
				producer.beginTransaction();
			}
			offsets.put(partition, new OffsetAndMetadata(record.offset() + 1));
			if (!events.isEmpty()) {
				producer.send(new ProducerRecord<>(EngineTopics.RESULTS, command.symbol(),
					mapper.writeValueAsString(EngineResult.of(command, events))));
			}
		}
		if (!offsets.isEmpty()) {
			producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
			producer.commitTransaction();
		}
	}

	// 결과까지 커밋한 다음 위치. 처음이면 0이다.
	private static Map<TopicPartition, Long> committed(KafkaConsumer<String, String> consumer,
			List<TopicPartition> partitions) {
		Map<TopicPartition, OffsetAndMetadata> saved = consumer.committed(Set.copyOf(partitions));
		Map<TopicPartition, Long> committed = new HashMap<>();
		for (TopicPartition partition : partitions) {
			OffsetAndMetadata offset = saved.get(partition);
			committed.put(partition, offset == null ? 0L : offset.offset());
		}
		return committed;
	}

	// 다시 시작하기 전에 잠시 기다린다.
	private void sleep() {
		try {
			Thread.sleep(RETRY_MILLIS);
		} catch (InterruptedException e) {
			running = false;
		}
	}

	// 읽은 위치는 결과와 함께 트랜잭션으로만 커밋한다.
	private Properties consumerConfig() {
		Properties config = new Properties();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ConsumerConfig.GROUP_ID_CONFIG, ID);
		config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
		config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
		// 발행기가 끝내지 못하고 취소한 묶음은 읽지 않는다.
		config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		return config;
	}

	// transactional.id를 고정해 엔진이 두 개 떠도 나중에 뜬 하나만 결과를 낼 수 있게 한다.
	private Properties producerConfig() {
		Properties config = new Properties();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
		config.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, ID);
		config.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, 10_000);
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		return config;
	}
}
