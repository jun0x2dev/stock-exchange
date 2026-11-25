package com.exchange.domain.outbox.entity;

import java.time.Instant;

import com.exchange.engine.EngineCommand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 엔진에 보낼 명령. 주문 내용은 주문 테이블에 있으므로 주문 ID만 가진다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Outbox {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long commandId;

	@Enumerated(EnumType.STRING)
	private EngineCommand.Type type;

	private String symbol;

	private long orderId;

	@Column(insertable = false, updatable = false)
	private Instant createdAt;

	private Instant publishedAt;

	/** 아직 보내지 않은 명령을 만든다. */
	public static Outbox create(EngineCommand.Type type, String symbol, long orderId) {
		Outbox outbox = new Outbox();
		outbox.type = type;
		outbox.symbol = symbol;
		outbox.orderId = orderId;
		return outbox;
	}
}
