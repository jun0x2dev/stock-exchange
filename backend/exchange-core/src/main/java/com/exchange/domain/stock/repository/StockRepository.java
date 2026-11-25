package com.exchange.domain.stock.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.exchange.domain.stock.entity.Stock;

/** 종목 저장소. */
public interface StockRepository extends JpaRepository<Stock, String> {

	/** 종목과 최종 체결가. 체결이 없으면 최종 체결가는 null이다. */
	interface StockPrice {

		/** 종목 코드. */
		String getSymbol();

		/** 종목명. */
		String getName();

		/** 기준가. */
		long getBasePrice();

		/** 거래 상태. */
		String getStatus();

		/** 최종 체결가. */
		Long getLastPrice();
	}

	/** 모든 종목과 최종 체결가를 조회한다. 종목 수는 운영자가 등록한 만큼으로 제한된다. */
	@Query(nativeQuery = true, value = """
		SELECT s.symbol, s.name, s.base_price AS basePrice, s.status,
		       (SELECT e.price FROM execution e WHERE e.symbol = s.symbol
		         ORDER BY e.event_seq DESC LIMIT 1) AS lastPrice
		  FROM stock s ORDER BY s.symbol
		""")
	List<StockPrice> findAllWithPrice();

	/** 멈춘 종목 코드. */
	@Query("SELECT s.symbol FROM Stock s WHERE s.status = com.exchange.domain.stock.entity.StockStatus.HALTED")
	Set<String> findHaltedSymbols();

	/** 정지는 풀렸지만 남은 결과를 아직 반영하지 않은 종목. */
	@Query("""
		SELECT s FROM Stock s
		 WHERE s.status = com.exchange.domain.stock.entity.StockStatus.TRADABLE AND s.haltOffset IS NOT NULL
		""")
	List<Stock> findResuming();

	/** 마지막으로 반영한 엔진 이벤트 순번. 영속성 컨텍스트의 값이 아니라 DB 값을 읽는다. */
	@Query("SELECT s.lastEventSeq FROM Stock s WHERE s.symbol = :symbol")
	Optional<Long> findLastEventSeq(String symbol);

	/** 순번이 기대한 값일 때만 앞으로 옮긴다. 반영이 이어졌으므로 다시 읽을 위치를 지운다. */
	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE Stock s SET s.lastEventSeq = :next, s.haltPartition = NULL, s.haltOffset = NULL
		 WHERE s.symbol = :symbol AND s.lastEventSeq = :expected
		""")
	int advanceEventSeq(String symbol, long expected, long next);

	/** 거래를 멈추고 다시 읽을 위치를 남긴다. 이미 남긴 위치가 더 앞이면 그대로 둔다. */
	@Modifying
	@Query(nativeQuery = true, value = """
		UPDATE stock SET status = 'HALTED', halt_partition = :partition,
		       halt_offset = LEAST(COALESCE(halt_offset, :offset), :offset)
		 WHERE symbol = :symbol
		""")
	int halt(String symbol, int partition, long offset);
}
