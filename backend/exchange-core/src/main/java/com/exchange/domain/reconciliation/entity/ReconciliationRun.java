package com.exchange.domain.reconciliation.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 대사 실행 한 번의 결과. 저장은 저장소의 INSERT로만 하며 바꾸지 않는다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationRun {

	@Id
	private Long runId;

	private LocalDate businessDate;

	@Enumerated(EnumType.STRING)
	private ReconciliationTrigger trigger;

	private Long requestedBy;

	@Enumerated(EnumType.STRING)
	private ReconciliationStatus status;

	private int invariantViolations;

	private int auditProblems;

	private long auditChecked;

	private Instant startedAt;

	private Instant finishedAt;
}
