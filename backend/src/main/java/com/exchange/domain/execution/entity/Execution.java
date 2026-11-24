package com.exchange.domain.execution.entity;

import java.time.Instant;

import com.exchange.engine.EngineEvent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 체결. 종목별 이벤트 순번으로 이중 반영을 막으며 INSERT만 한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long executionId;

	private String symbol;

	private long eventSeq;

	private long buyOrderId;

	private long sellOrderId;

	private long price;

	private long quantity;

	// 앱 시계가 아니라 DB 시계로 남긴다.
	@Column(insertable = false, updatable = false)
	private Instant executedAt;

	/** 엔진의 체결 이벤트에서 만든다. */
	public static Execution of(String symbol, EngineEvent.Traded event) {
		Execution execution = new Execution();
		execution.symbol = symbol;
		execution.eventSeq = event.seq();
		execution.buyOrderId = event.buyOrderId();
		execution.sellOrderId = event.sellOrderId();
		execution.price = event.price();
		execution.quantity = event.quantity();
		return execution;
	}
}
