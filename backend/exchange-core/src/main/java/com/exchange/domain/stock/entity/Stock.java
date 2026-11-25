package com.exchange.domain.stock.entity;

import com.exchange.domain.stock.exception.StockException;
import com.exchange.global.error.ErrorCode;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 거래 종목. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {

	@Id
	private String symbol;

	private String name;

	private long basePrice;

	@Enumerated(EnumType.STRING)
	private StockStatus status;

	private long lastEventSeq;

	private Integer haltPartition;

	private Long haltOffset;

	/** 주문을 받을 수 있는 종목과 가격인지 검증한다. */
	public void validateOrder(long price) {
		if (status != StockStatus.TRADABLE) {
			throw new StockException(ErrorCode.STOCK_HALTED);
		}
		if (price < PricePolicy.lowerLimit(basePrice) || price > PricePolicy.upperLimit(basePrice)) {
			throw new StockException(ErrorCode.PRICE_OUT_OF_RANGE);
		}
		if (!PricePolicy.isOnTick(price)) {
			throw new StockException(ErrorCode.INVALID_TICK_SIZE);
		}
	}
}
