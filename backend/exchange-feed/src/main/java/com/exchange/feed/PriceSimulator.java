package com.exchange.feed;

import java.util.Random;

import com.exchange.market.PricePolicy;

/** 한 종목의 모의 시세. 기준가 근처를 오르내리며, 같은 시드에서는 항상 같은 흐름이 나온다. */
public final class PriceSimulator {

	/** 기준가에서 이 비율(%)까지만 벗어난다. */
	static final int RANGE_PERCENT = 10;

	// 한 번에 움직일 확률. 1초마다 불리므로 평균 4초에 한 번 움직인다.
	private static final double MOVE = 0.25;

	private final long basePrice;
	private final Random random;
	private long price;

	/** 기준가에서 시작한다. */
	public PriceSimulator(long basePrice, long seed) {
		this.basePrice = basePrice;
		this.price = basePrice;
		this.random = new Random(seed);
	}

	/** 다음 가격. 기준가에서 멀어질수록 돌아올 확률이 커진다. */
	public long next() {
		if (random.nextDouble() < MOVE) {
			double distance = (double) (price - basePrice) / (basePrice * RANGE_PERCENT / 100);
			boolean up = random.nextDouble() < 0.5 - 0.5 * distance;
			price = up ? PricePolicy.above(price) : PricePolicy.below(price);
		}
		return price;
	}
}
