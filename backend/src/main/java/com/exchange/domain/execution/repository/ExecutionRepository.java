package com.exchange.domain.execution.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.exchange.domain.execution.entity.Execution;

/** 체결 저장소. 수정·삭제 메서드를 두지 않는다. */
public interface ExecutionRepository extends Repository<Execution, Long> {

	/** 내 체결 조회 결과. 상대방 정보는 담지 않는다. */
	interface MyExecution {

		/** 체결 ID. */
		long getExecutionId();

		/** 내 주문 ID. */
		long getOrderId();

		/** 종목 코드. */
		String getSymbol();

		/** 매수·매도. */
		String getSide();

		/** 체결가. */
		long getPrice();

		/** 체결 수량. */
		long getQuantity();

		/** 체결 시각. */
		Instant getExecutedAt();
	}

	/** 체결을 추가한다. */
	Execution save(Execution execution);

	/** 종목의 최근 체결. */
	List<Execution> findBySymbolOrderByEventSeqDesc(String symbol, Limit limit);

	/** 계좌의 체결을 최신순으로 조회한다. 매수·매도를 나눠 각각의 인덱스를 쓴다. */
	@Query(nativeQuery = true, value = """
		SELECT e.execution_id AS executionId, o.order_id AS orderId, o.symbol AS symbol, o.side AS side,
		       e.price AS price, e.quantity AS quantity, e.executed_at AS executedAt
		  FROM orders o JOIN execution e ON e.buy_order_id = o.order_id
		 WHERE o.account_id = :accountId AND o.side = 'BUY' AND e.execution_id < :cursor
		UNION ALL
		SELECT e.execution_id, o.order_id, o.symbol, o.side, e.price, e.quantity, e.executed_at
		  FROM orders o JOIN execution e ON e.sell_order_id = o.order_id
		 WHERE o.account_id = :accountId AND o.side = 'SELL' AND e.execution_id < :cursor
		 ORDER BY executionId DESC
		 LIMIT :size
		""")
	List<MyExecution> findByAccount(long accountId, long cursor, int size);
}
