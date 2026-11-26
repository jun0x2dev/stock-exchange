package com.exchange.domain.reconciliation.dto;

import java.util.List;

import com.exchange.domain.reconciliation.entity.ReconciliationViolation;

/** 대사 결과와 불일치 내역의 앞부분. */
public record ReconciliationDetail(ReconciliationResponse run, List<Violation> violations) {

	/** 불일치 한 건. */
	public record Violation(String kind, String detail) {

		/** 엔티티에서 만든다. */
		public static Violation from(ReconciliationViolation violation) {
			return new Violation(violation.getKind(), violation.getDetail());
		}
	}
}
