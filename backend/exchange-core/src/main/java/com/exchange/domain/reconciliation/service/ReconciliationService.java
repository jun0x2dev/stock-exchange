package com.exchange.domain.reconciliation.service;

import java.util.Optional;

import com.exchange.domain.reconciliation.dto.ReconciliationDetail;
import com.exchange.domain.reconciliation.dto.ReconciliationResponse;
import com.exchange.global.response.CursorPage;

/** 잔고와 원장, 주문과 체결, 감사 로그가 서로 맞는지 대사하고 결과를 남긴다. */
public interface ReconciliationService {

	/** 직원이 지금 대사한다. 다른 대사가 진행 중이면 거부한다. */
	ReconciliationResponse run(long userId);

	/** 전날의 정기 대사가 없으면 실행한다. 이미 있거나 다른 대사가 진행 중이면 비어 있다. */
	Optional<ReconciliationResponse> runScheduled();

	/** 대사 이력을 최신순으로 조회한다. */
	CursorPage<ReconciliationResponse> find(Long cursor, int size);

	/** 대사 결과와 불일치 내역을 조회한다. */
	ReconciliationDetail get(long runId);
}
