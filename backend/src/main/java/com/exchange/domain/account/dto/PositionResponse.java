package com.exchange.domain.account.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.exchange.domain.account.entity.Position;
import com.exchange.domain.stock.dto.StockResponse;

/** 보유 종목과 손익. 평균단가는 표시용이라 소수 둘째 자리에서 반올림한다. */
public record PositionResponse(String symbol, String name, long quantity, long sellableQuantity,
		BigDecimal averagePrice, long totalCost, long lastPrice, long evaluation, long unrealizedPnl,
		long realizedPnl) {

	/** 보유 종목을 현재가로 평가한다. */
	public static PositionResponse of(Position position, StockResponse stock) {
		long quantity = position.getQuantity();
		long evaluation = Math.multiplyExact(quantity, stock.lastPrice());
		BigDecimal average = quantity == 0 ? BigDecimal.ZERO
			: BigDecimal.valueOf(position.getTotalCost()).divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
		return new PositionResponse(stock.symbol(), stock.name(), quantity,
			quantity - position.getReservedQuantity(), average, position.getTotalCost(), stock.lastPrice(),
			evaluation, Math.subtractExact(evaluation, position.getTotalCost()), position.getRealizedPnl());
	}
}
