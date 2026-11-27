package com.exchange.domain.bot.listener;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.exchange.domain.bot.service.SpoofScenarioService;
import com.exchange.domain.stock.dto.StockResponse;
import com.exchange.domain.stock.entity.StockStatus;
import com.exchange.domain.stock.service.StockService;
import com.exchange.engine.Side;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 탐지 실험용 공격자. 설정으로 켰을 때만 동작하며 호가 봇과 함께 쓴다.
 *
 * <p>종목을 돌아가며 스푸핑 두 번(매수·매도 쪽 가짜 주문)과 미끼 한 번을 번갈아 한다. 가짜 주문은 잠시 둔 뒤 취소한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("exchange.bot.spoofer.enabled")
public class SpoofScenarioRunner {

	private static final long PERIOD_SECONDS = 8;
	private static final long HOLD_MILLIS = 1_500;

	private final SpoofScenarioService scenarioService;
	private final StockService stockService;
	private final ScheduledExecutorService worker =
		Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "spoofer"));
	private long step;

	/** 기동이 끝나고 호가가 쌓일 때까지 기다린 뒤 시작한다. */
	@EventListener(ApplicationReadyEvent.class)
	void start() {
		worker.scheduleWithFixedDelay(this::run, 30, PERIOD_SECONDS, TimeUnit.SECONDS);
	}

	/** 진행 중인 시나리오를 마치고 멈춘다. */
	@PreDestroy
	void stop() throws InterruptedException {
		worker.shutdown();
		worker.awaitTermination(10, TimeUnit.SECONDS);
	}

	// 정해진 순서로 종목과 행동을 고른다. 같은 설정이면 같은 순서로 반복한다.
	private void run() {
		try {
			List<StockResponse> stocks = stockService.findAll().stream()
				.filter(stock -> stock.status() == StockStatus.TRADABLE).toList();
			if (stocks.isEmpty()) {
				return;
			}
			String symbol = stocks.get((int) (step % stocks.size())).symbol();
			int kind = (int) (step++ % 3);
			var order = kind == 2 ? scenarioService.decoy(symbol, Side.BUY)
				: scenarioService.spoof(symbol, kind == 0 ? Side.BUY : Side.SELL);
			if (order.isPresent()) {
				Thread.sleep(HOLD_MILLIS);
				scenarioService.cancel(order.get());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			log.error("공격 시나리오 실패", e);
		}
	}
}
