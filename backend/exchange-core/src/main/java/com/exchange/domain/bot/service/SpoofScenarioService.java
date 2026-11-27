package com.exchange.domain.bot.service;

import java.util.Optional;

import com.exchange.engine.Side;

/**
 * 탐지 실험용 공격 시나리오. 정답을 알 수 있도록 멱등키 앞에 표시를 붙인다.
 *
 * <p>스푸핑의 가짜 주문은 {@code spoof-}, 미끼(큰 주문을 냈다가 거래 없이 취소)는 {@code decoy-}로 시작한다.
 */
public interface SpoofScenarioService {

	/** 가짜 큰 주문을 낸 뒤 반대편에서 소량을 실제로 체결시키고, 가짜 주문 번호를 반환한다. 호가가 없으면 비어 있다. */
	Optional<Long> spoof(String symbol, Side fakeSide);

	/** 체결되지 않을 가격에 큰 주문만 내고 번호를 반환한다. 반대편 거래는 하지 않는다. */
	Optional<Long> decoy(String symbol, Side side);

	/** 주문을 취소한다. 그사이 모두 체결됐으면 아무 일도 하지 않는다. */
	void cancel(long orderId);
}
