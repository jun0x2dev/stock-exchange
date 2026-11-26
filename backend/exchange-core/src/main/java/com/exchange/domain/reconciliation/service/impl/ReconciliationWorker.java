package com.exchange.domain.reconciliation.service.impl;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.exchange.domain.reconciliation.service.ReconciliationService;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 한 시간마다 전날의 정기 대사가 있는지 보고 없으면 실행한다.
 *
 * <p>자정에 한 번만 돌리면 그 시각에 꺼져 있던 날을 건너뛴다. 날짜마다 한 번만 기록되므로 자주 확인해도 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationWorker {

	private final ReconciliationService reconciliationService;
	private final ScheduledExecutorService worker =
		Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "reconciliation"));

	/** 기동이 끝나고 1분 뒤부터 시작한다. 밀린 체결 반영이 먼저 끝나게 한다. */
	@EventListener(ApplicationReadyEvent.class)
	void start() {
		worker.scheduleWithFixedDelay(this::run, 1, 60, TimeUnit.MINUTES);
	}

	/** 진행 중인 대사를 마치고 멈춘다. */
	@PreDestroy
	void stop() throws InterruptedException {
		worker.shutdown();
		worker.awaitTermination(30, TimeUnit.SECONDS);
	}

	// 실패해도 다음 주기에 다시 한다.
	private void run() {
		try {
			reconciliationService.runScheduled();
		} catch (RuntimeException e) {
			log.error("정기 대사 실패", e);
		}
	}
}
