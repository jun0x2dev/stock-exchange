package com.exchange.domain.reconciliation.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 대사에서 찾은 불일치 한 건. {@code kind}는 불변식 번호(I1~I7) 또는 AUDIT이다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationViolation {

	@Id
	private Long violationId;

	private long runId;

	private String kind;

	private String detail;
}
