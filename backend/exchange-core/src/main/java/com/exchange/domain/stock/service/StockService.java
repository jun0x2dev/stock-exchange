package com.exchange.domain.stock.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.exchange.domain.stock.dto.StockResponse;
import com.exchange.domain.stock.entity.Stock;

/** 종목 조회와 거래 정지. */
public interface StockService {

	/** 모든 종목의 현재가를 조회한다. */
	List<StockResponse> findAll();

	/** 종목을 찾는다. 없으면 예외를 던진다. */
	Stock get(String symbol);

	/** 종목의 거래를 정지하고, 다시 열 때 읽을 결과의 위치를 남긴다. */
	void halt(String symbol, int partition, long offset);

	/** 멈춘 종목 코드. */
	Set<String> findHalted();

	/** 정지가 풀린 종목을 위해 다시 읽어야 하는 파티션과 위치. */
	Map<Integer, Long> findResumeOffsets();
}
