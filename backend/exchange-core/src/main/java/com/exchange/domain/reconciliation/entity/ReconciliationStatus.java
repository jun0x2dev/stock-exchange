package com.exchange.domain.reconciliation.entity;

/** 대사 결과. 불변식 위반이나 감사 로그 문제가 하나라도 있으면 실패다. */
public enum ReconciliationStatus {
	PASSED, FAILED
}
