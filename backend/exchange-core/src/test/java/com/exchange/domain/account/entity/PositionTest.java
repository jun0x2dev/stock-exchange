package com.exchange.domain.account.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 원가와 실현손익 계산을 검증한다. */
class PositionTest {

	@Test
	@DisplayName("나눠 팔아도 원가의 반올림 오차가 남지 않는다")
	void costHasNoRoundingError() {
		Position position = new Position();
		position.buy(3, 1_000);
		sell(position, 1, 400);
		assertThat(position.getTotalCost()).isEqualTo(667);
		sell(position, 1, 400);
		sell(position, 1, 400);
		assertThat(position.getTotalCost()).isZero();
		assertThat(position.getRealizedPnl()).isEqualTo(200);
	}

	@Test
	@DisplayName("원가와 수량의 곱이 long을 넘어도 계산한다")
	void costDoesNotOverflow() {
		Position position = new Position();
		position.buy(10_000_000_000L, 5_000_000_000_000_000L);
		sell(position, 5_000_000_000L, 0);
		assertThat(position.getTotalCost()).isEqualTo(2_500_000_000_000_000L);
	}

	// 예약을 풀며 판다. 예약은 DB에서 하므로 음수로 시작해 맞춘다.
	private static void sell(Position position, long quantity, long amount) {
		position.release(-quantity);
		position.sell(quantity, amount);
	}
}
