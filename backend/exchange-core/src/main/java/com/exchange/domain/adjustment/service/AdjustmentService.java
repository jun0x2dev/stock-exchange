package com.exchange.domain.adjustment.service;

import com.exchange.domain.adjustment.dto.AdjustmentRequest;
import com.exchange.domain.adjustment.dto.AdjustmentResponse;
import com.exchange.global.response.CursorPage;

/** 원장 정정. 한 사람이 요청하고 다른 사람이 승인해야 반대 전표가 기록된다. */
public interface AdjustmentService {

	/** 입금·입고를 되돌려 달라고 요청한다. 잔고는 바뀌지 않는다. */
	AdjustmentResponse request(long userId, AdjustmentRequest request);

	/** 요청을 승인하고 반대 전표를 기록한다. 요청한 사람은 승인할 수 없다. */
	AdjustmentResponse approve(long userId, long adjustmentId);

	/** 요청을 거절한다. 요청한 사람은 거절할 수 없다. */
	AdjustmentResponse reject(long userId, long adjustmentId);

	/** 정정 요청을 최신순으로 조회한다. */
	CursorPage<AdjustmentResponse> find(Long cursor, int size);
}
