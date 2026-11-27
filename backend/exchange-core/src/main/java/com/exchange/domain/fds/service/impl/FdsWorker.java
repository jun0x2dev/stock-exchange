package com.exchange.domain.fds.service.impl;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.exchange.domain.fds.service.FdsService;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 10초마다 이상거래를 탐지한다. 주문 처리 경로와 분리해, 탐지가 느리거나 실패해도 거래에는 영향이 없다.
 *
 * <p>최근 10분의 기록을 매번 다시 보며 같은 사건은 한 번만 남긴다. 10분 넘게 꺼져 있었으면 그 사이는 놓친다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FdsWorker {

	private final FdsService fdsService;
	private final ScheduledExecutorService worker =
		Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "fds"));

	/** 기동이 끝나면 시작한다. */
	@EventListener(ApplicationReadyEvent.class)
	void start() {
		worker.scheduleWithFixedDelay(this::run, 10, 10, TimeUnit.SECONDS);
	}

	/** 진행 중인 탐지를 마치고 멈춘다. */
	@PreDestroy
	void stop() throws InterruptedException {
		worker.shutdown();
		worker.awaitTermination(10, TimeUnit.SECONDS);
	}

	// 실패해도 다음 주기에 다시 한다.
	private void run() {
		try {
			fdsService.detect();
		} catch (RuntimeException e) {
			log.error("이상거래 탐지 실패", e);
		}
	}
}
