package com.exchange.domain.stock.dto;

import com.exchange.domain.stock.entity.PricePolicy;
import com.exchange.domain.stock.entity.StockStatus;
import com.exchange.domain.stock.repository.StockRepository.StockPrice;

/** 종목과 현재가. 체결이 없으면 기준가를 현재가로 쓴다. */
public record StockResponse(String symbol, String name, StockStatus status, long basePrice, long lastPrice,
		long upperLimit, long lowerLimit) {

	/** 조회 결과에서 만든다. */
	public static StockResponse from(StockPrice row) {
		long lastPrice = row.getLastPrice() == null ? row.getBasePrice() : row.getLastPrice();
		return new StockResponse(row.getSymbol(), row.getName(), StockStatus.valueOf(row.getStatus()),
			row.getBasePrice(), lastPrice, PricePolicy.upperLimit(row.getBasePrice()),
			PricePolicy.lowerLimit(row.getBasePrice()));
	}
}
