package com.exchange.domain.execution.listener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import com.exchange.domain.execution.exception.EngineMismatchException;
import com.exchange.domain.execution.service.ExecutionService;
import com.exchange.domain.stock.service.StockService;
import com.exchange.engine.EngineResult;
import com.exchange.global.config.KafkaConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 엔진 결과를 종목별로 반영한다.
 *
 * <p>DB에 커밋한 뒤에 읽은 위치를 커밋하므로 같은 결과가 다시 올 수 있고, 종목별 순번으로 버린다. 반영할 수 없는
 * 결과는 건너뛰지 않고 그 종목만 멈춘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EngineResultListener implements ConsumerSeekAware {

	/** 리스너를 멈추고 다시 시작할 때 쓰는 ID. */
	public static final String ID = "engine-result";

	// 커밋마다 디스크 동기화를 기다리므로 여러 결과를 한 번에 커밋한다. 크면 계좌 잠금을 오래 잡는다.
	private static final int SETTLE_BATCH = 20;
	private static final int MAX_FAILURES = 3;

	private final ExecutionService executionService;
	private final StockService stockService;
	private final JsonMapper jsonMapper;

	/** 정지가 풀린 종목은 멈출 때 남긴 위치부터 다시 읽는다. 다른 종목의 결과는 순번으로 버려진다. */
	@Override
	public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
		stockService.findResumeOffsets().forEach((partition, offset) -> {
			if (assignments.keySet().stream().anyMatch(tp -> tp.partition() == partition)) {
				log.info("멈췄던 위치부터 다시 읽는다: partition={}, offset={}", partition, offset);
				callback.seek(KafkaConfig.RESULTS, partition, offset);
			}
		});
	}

	/** 종목별로 나눠 반영한다. 멈춘 종목의 결과는 남겨 두고 다른 종목을 계속 처리한다. */
	@KafkaListener(id = ID, topics = KafkaConfig.RESULTS, groupId = "exchange-core")
	void onResults(List<ConsumerRecord<String, String>> records) {
		Map<String, List<ConsumerRecord<String, String>>> bySymbol = records.stream()
			.collect(Collectors.groupingBy(ConsumerRecord::key, LinkedHashMap::new, Collectors.toList()));
		Set<String> halted = stockService.findHalted();
		bySymbol.forEach((symbol, symbolRecords) -> {
			if (!halted.contains(symbol)) {
				settle(symbol, symbolRecords);
			}
		});
	}

	// 묶어서 반영하다가 실패하면 하나씩 다시 시도하고, 그래도 안 되면 종목을 멈춘다.
	private void settle(String symbol, List<ConsumerRecord<String, String>> records) {
		int from = 0;
		int size = SETTLE_BATCH;
		int failures = 0;
		while (from < records.size()) {
			List<ConsumerRecord<String, String>> batch = records.subList(from, Math.min(from + size, records.size()));
			try {
				executionService.settle(symbol, batch.stream()
					.map(record -> jsonMapper.readValue(record.value(), EngineResult.class)).toList());
				from += batch.size();
				failures = 0;
			} catch (EngineMismatchException e) {
				if (batch.size() == 1) {
					// 빠진 결과가 앞에 있을 수 있으므로 처음부터 다시 읽게 한다.
					halt(symbol, batch.getFirst().partition(), 0, e);
					return;
				}
				size = 1;
			} catch (RuntimeException e) {
				if (size > 1) {
					size = 1;
				} else if (++failures == MAX_FAILURES) {
					halt(symbol, batch.getFirst().partition(), batch.getFirst().offset(), e);
					return;
				} else {
					sleep(failures);
				}
			}
		}
	}

	// 다시 읽을 위치를 남기고 멈춘다. 저장하지 못하면 예외가 나가 같은 결과가 다시 전달된다.
	private void halt(String symbol, int partition, long offset, RuntimeException cause) {
		log.error("종목 거래 정지: symbol={}, partition={}, offset={}", symbol, partition, offset, cause);
		stockService.halt(symbol, partition, offset);
	}

	// 일시적인 실패가 풀릴 시간을 준다.
	private static void sleep(int seconds) {
		try {
			Thread.sleep(seconds * 1_000L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
