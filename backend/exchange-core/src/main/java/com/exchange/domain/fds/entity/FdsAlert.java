package com.exchange.domain.fds.entity;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 이상거래 경보 한 건. 저장은 저장소의 INSERT로만 하며 바꾸지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FdsAlert {

	@Id
	private Long alertId;

	@Enumerated(EnumType.STRING)
	private FdsRule rule;

	private String dedupeKey;

	private long accountId;

	private String symbol;

	private Long orderId;

	private String evidence;

	private Instant detectedAt;
}
