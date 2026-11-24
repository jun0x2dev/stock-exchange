package com.exchange.domain.matching.service;

import com.exchange.engine.BookSnapshot;

/** 종목별 단일 스레드로 매칭 엔진을 돌린다. */
public interface MatchingService {

	/** 종목에 처리할 주문이나 취소가 있다고 알린다. 여러 번 불러도 한 번만 예약된다. */
	void signal(String symbol);

	/** 메모리의 호가창을 버리고 DB에서 다시 만들게 한다. */
	void reload(String symbol);

	/** 커밋된 결과까지 반영된 호가창. */
	BookSnapshot snapshot(String symbol);
}
