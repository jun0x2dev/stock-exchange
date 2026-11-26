package com.exchange.domain.reconciliation.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.exchange.domain.reconciliation.entity.ReconciliationRun;
import com.exchange.domain.reconciliation.entity.ReconciliationStatus;
import com.exchange.domain.reconciliation.entity.ReconciliationTrigger;

/** 대사 실행 한 번의 결과. */
public record ReconciliationResponse(long runId, LocalDate businessDate, ReconciliationTrigger trigger,
		ReconciliationStatus status, int invariantViolations, int auditProblems, long auditChecked, Instant startedAt,
		Instant finishedAt) {

	/** 엔티티에서 만든다. */
	public static ReconciliationResponse from(ReconciliationRun run) {
		return new ReconciliationResponse(run.getRunId(), run.getBusinessDate(), run.getTrigger(), run.getStatus(),
			run.getInvariantViolations(), run.getAuditProblems(), run.getAuditChecked(), run.getStartedAt(),
			run.getFinishedAt());
	}
}
