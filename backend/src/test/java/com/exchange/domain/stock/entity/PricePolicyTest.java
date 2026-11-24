package com.exchange.domain.stock.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 호가 단위와 가격제한폭을 검증한다. */
class PricePolicyTest {

	@ParameterizedTest
	@CsvSource({ "1999,1", "2000,5", "4995,5", "5000,10", "19990,10", "20000,50", "49950,50", "50000,100", "199900,100",
		"200000,500", "499500,500", "500000,1000" })
	@DisplayName("가격대 경계에서 호가 단위가 바뀐다")
	void tickSize(long price, long tick) {
		assertThat(PricePolicy.tickSize(price)).isEqualTo(tick);
	}

	@Test
	@DisplayName("KRX 공식 예시와 상·하한가가 같다")
	void limitsMatchKrxExample() {
		assertThat(PricePolicy.upperLimit(9_980)).isEqualTo(12_970);
		assertThat(PricePolicy.lowerLimit(9_980)).isEqualTo(6_990);
	}

	@Test
	@DisplayName("상·하한가는 항상 그 가격대의 호가 단위에 맞는다")
	void limitsAreOnTick() {
		for (long base = 1; base < 700_000; base += PricePolicy.tickSize(base)) {
			assertThat(PricePolicy.isOnTick(PricePolicy.upperLimit(base))).isTrue();
			assertThat(PricePolicy.isOnTick(PricePolicy.lowerLimit(base))).isTrue();
		}
	}
}
