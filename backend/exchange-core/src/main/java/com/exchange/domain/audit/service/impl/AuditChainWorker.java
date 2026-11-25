package com.exchange.domain.audit.service.impl;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.exchange.domain.audit.service.AuditChainService;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 감사 로그를 1초마다 체인에 넣고, 1분마다 마지막 값을 DB 밖에 남긴다. 간격이 짧을수록 몰래 고칠 틈이 줄어든다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditChainWorker {

	private final AuditChainService chainService;
	private final ScheduledExecutorService worker =
		Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "audit-chain"));

	/** 기동이 끝나면 시작한다. */
	@EventListener(ApplicationReadyEvent.class)
	void start() {
		worker.scheduleWithFixedDelay(() -> run(this::extendAll), 1, 1, TimeUnit.SECONDS);
		worker.scheduleWithFixedDelay(() -> run(chainService::anchor), 60, 60, TimeUnit.SECONDS);
	}

	/** 처리 중인 묶음을 마치고 멈춘다. */
	@PreDestroy
	void stop() throws InterruptedException {
		worker.shutdown();
		worker.awaitTermination(10, TimeUnit.SECONDS);
	}

	// 밀린 로그를 모두 넣는다.
	private void extendAll() {
		while (chainService.extend() > 0) {
			// 한 묶음을 넣었으면 더 남아 있을 수 있다.
		}
	}

	// 실패해도 다음 주기에 다시 한다.
	private void run(Runnable task) {
		try {
			task.run();
		} catch (RuntimeException e) {
			log.error("감사 로그 체인 처리 실패", e);
		}
	}
}
