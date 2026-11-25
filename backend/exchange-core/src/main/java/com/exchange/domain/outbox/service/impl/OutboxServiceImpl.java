package com.exchange.domain.outbox.service.impl;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.outbox.entity.Outbox;
import com.exchange.domain.outbox.repository.OutboxRepository;
import com.exchange.domain.outbox.service.OutboxAppended;
import com.exchange.domain.outbox.service.OutboxService;
import com.exchange.engine.EngineCommand;

import lombok.RequiredArgsConstructor;

/** 명령 저장 구현. 호출한 쪽의 트랜잭션이 없으면 실패한다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

	private final OutboxRepository outboxRepository;
	private final ApplicationEventPublisher eventPublisher;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void append(EngineCommand.Type type, String symbol, long orderId) {
		outboxRepository.save(Outbox.create(type, symbol, orderId));
		eventPublisher.publishEvent(new OutboxAppended());
	}
}
