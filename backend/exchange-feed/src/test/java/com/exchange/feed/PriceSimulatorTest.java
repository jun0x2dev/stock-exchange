package com.exchange.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.exchange.market.PricePolicy;

/** 모의 시세의 범위와 재현성을 검증한다. */
class PriceSimulatorTest {

	@ParameterizedTest(name = "기준가 {0}")
	@ValueSource(longs = { 71_000, 182_500, 45_350, 236_500, 18_420, 4_985 })
	@DisplayName("호가 단위를 지키고 기준가의 10% 안에서 움직인다")
	void staysOnTickAndInRange(long base) {
		PriceSimulator simulator = new PriceSimulator(base, 1);
		Set<Long> prices = new HashSet<>();
		for (int i = 0; i < 100_000; i++) {
			long price = simulator.next();
			prices.add(price);
			assertThat(PricePolicy.isOnTick(price)).isTrue();
			assertThat(Math.abs(price - base)).isLessThanOrEqualTo(base * PriceSimulator.RANGE_PERCENT / 100 + PricePolicy.tickSize(price));
		}
		assertThat(prices).hasSizeGreaterThan(5);
	}

	@Test
	@DisplayName("같은 시드에서는 같은 흐름이 나온다")
	void sameSeedSamePrices() {
		PriceSimulator a = new PriceSimulator(71_000, 42);
		PriceSimulator b = new PriceSimulator(71_000, 42);
		for (int i = 0; i < 1_000; i++) {
			assertThat(a.next()).isEqualTo(b.next());
		}
	}
}
