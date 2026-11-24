package com.exchange.domain.stock.service;

import java.util.List;

import com.exchange.domain.stock.dto.StockResponse;
import com.exchange.domain.stock.entity.Stock;

/** 종목 조회와 거래 정지. */
public interface StockService {

	/** 모든 종목의 현재가를 조회한다. */
	List<StockResponse> findAll();

	/** 종목을 찾는다. 없으면 예외를 던진다. */
	Stock get(String symbol);

	/** 종목의 거래를 정지한다. */
	void halt(String symbol);
}
