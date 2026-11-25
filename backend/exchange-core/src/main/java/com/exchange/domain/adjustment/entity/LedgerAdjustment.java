package com.exchange.domain.adjustment.entity;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 원장 정정 요청. 저장과 상태 변경은 저장소의 INSERT와 조건부 UPDATE로만 한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerAdjustment {

	@Id
	private Long adjustmentId;

	private long depositId;

	private String reason;

	@Enumerated(EnumType.STRING)
	private AdjustmentStatus status;

	private long requestedBy;

	private Long decidedBy;

	private Instant createdAt;

	private Instant decidedAt;
}
