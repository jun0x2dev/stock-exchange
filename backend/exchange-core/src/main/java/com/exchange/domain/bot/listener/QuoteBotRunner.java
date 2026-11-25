package com.exchange.domain.bot.listener;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.exchange.domain.bot.service.QuoteBoard;
import com.exchange.domain.bot.service.QuoteBotService;
import com.exchange.domain.stock.dto.StockResponse;
import com.exchange.domain.stock.entity.StockStatus;
import com.exchange.domain.stock.service.StockService;
import com.exchange.global.config.KafkaConfig;
import com.exchange.global.error.BusinessException;
import com.exchange.market.Quote;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시세를 받아 봇을 주기적으로 움직인다. 설정으로 켰을 때만 동작한다.
 *
 * <p>시세가 끊기거나 처음부터 없으면 호가를 거둔다. 옛 가격의 호가가 남아 체결되는 것을 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("exchange.bot.enabled")
public class QuoteBotRunner {

	private static final long PERIOD_MILLIS = 2_000;
	private static final int TRADE_ONE_IN = 3;
	private static final int MAX_TRADE_QUANTITY = 10;

	private final QuoteBotService quoteBotService;
	private final StockService stockService;
	private final JsonMapper jsonMapper;
	private final QuoteBoard board = new QuoteBoard(System::nanoTime);
	private final Set<String> withdrawn = new HashSet<>();
	private final ScheduledExecutorService worker =
		Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "quote-bot"));

	/** 시세를 기록한다. 잘못된 시세는 다음 시세가 대신하므로 버린다. */
	@KafkaListener(topics = KafkaConfig.QUOTES, groupId = "quote-bot", properties = "auto.offset.reset=latest")
	void onQuotes(List<ConsumerRecord<String, String>> records) {
		for (ConsumerRecord<String, String> record : records) {
			try {
				board.update(jsonMapper.readValue(record.value(), Quote.class));
			} catch (JacksonException e) {
				log.warn("읽을 수 없는 시세를 버린다: offset={}", record.offset());
			}
		}
	}

	/** 기동이 끝나면 움직이기 시작한다. */
	@EventListener(ApplicationReadyEvent.class)
	void start() {
		worker.scheduleWithFixedDelay(this::run, PERIOD_MILLIS, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
	}

	/** 처리 중인 종목을 마치고 멈춘다. */
	@PreDestroy
	void stop() throws InterruptedException {
		worker.shutdown();
		worker.awaitTermination(10, TimeUnit.SECONDS);
	}

	// 한 종목이 실패해도 다른 종목은 계속한다.
	private void run() {
		try {
			for (StockResponse stock : stockService.findAll()) {
				try {
					step(stock);
				} catch (BusinessException e) {
					log.warn("봇 주문 거부: symbol={}, code={}", stock.symbol(), e.getErrorCode());
				}
			}
		} catch (RuntimeException e) {
			log.error("봇 실행 실패", e);
		}
	}

	// 시세가 있으면 호가를 맞추고 가끔 거래한다. 없으면 한 번 거둔다.
	private void step(StockResponse stock) {
		String symbol = stock.symbol();
		OptionalLong price = board.fresh(symbol);
		if (price.isEmpty() || stock.status() != StockStatus.TRADABLE) {
			if (!withdrawn.contains(symbol)) {
				quoteBotService.withdraw(symbol);
				withdrawn.add(symbol);
				log.info("시세가 없어 호가를 거둔다: symbol={}", symbol);
			}
			return;
		}
		withdrawn.remove(symbol);
		quoteBotService.quote(symbol, price.getAsLong());
		ThreadLocalRandom random = ThreadLocalRandom.current();
		if (random.nextInt(TRADE_ONE_IN) == 0) {
			quoteBotService.trade(symbol, random.nextBoolean(), 1 + random.nextInt(MAX_TRADE_QUANTITY));
		}
	}
}
