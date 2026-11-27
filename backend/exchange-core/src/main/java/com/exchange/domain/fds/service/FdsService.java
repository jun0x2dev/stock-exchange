package com.exchange.domain.fds.service;

import com.exchange.domain.fds.dto.FdsAlertResponse;
import com.exchange.global.response.CursorPage;

/** 규칙 기반 이상거래 탐지. 주문·체결 처리와 분리해 기록을 사후에 훑는다. */
public interface FdsService {

	/** 모든 규칙으로 최근 기록을 검사하고, 새로 남긴 경보 수를 반환한다. 여러 번 실행해도 같은 사건은 한 번만 남는다. */
	int detect();

	/** 경보를 최신순으로 조회한다. */
	CursorPage<FdsAlertResponse> find(Long cursor, int size);
}
