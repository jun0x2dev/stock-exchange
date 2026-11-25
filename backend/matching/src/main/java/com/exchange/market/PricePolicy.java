package com.exchange.market;

/**
 * 호가 단위와 가격제한폭.
 *
 * <p>KRX 공식 자료의 호가 단위표와 계산 순서를 따른다. 자료에 마지막 단계의 반올림 방향이 없어
 * 상한가는 내림, 하한가는 올림으로 정했다(프로젝트 정책).
 */
public final class PricePolicy {

	private PricePolicy() {
	}

	/** 가격대에 맞는 호가 단위. */
	public static long tickSize(long price) {
		if (price < 2_000) {
			return 1;
		}
		if (price < 5_000) {
			return 5;
		}
		if (price < 20_000) {
			return 10;
		}
		if (price < 50_000) {
			return 50;
		}
		if (price < 200_000) {
			return 100;
		}
		return price < 500_000 ? 500 : 1_000;
	}

	/** 호가 단위에 맞는 가격인지 확인한다. */
	public static boolean isOnTick(long price) {
		return price % tickSize(price) == 0;
	}

	/** 한 호가 위의 가격. */
	public static long above(long price) {
		return price + tickSize(price);
	}

	/** 한 호가 아래의 가격. 가격대 경계에서는 아래쪽 호가 단위를 쓴다. */
	public static long below(long price) {
		return price - tickSize(price - 1);
	}

	/** 상한가. */
	public static long upperLimit(long basePrice) {
		long limit = Math.addExact(basePrice, change(basePrice));
		return limit - limit % tickSize(limit);
	}

	/** 하한가. */
	public static long lowerLimit(long basePrice) {
		long limit = basePrice - change(basePrice);
		long tick = tickSize(limit);
		return Math.ceilDiv(limit, tick) * tick;
	}

	// 기준가의 30%를 기준가의 호가 단위로 내림한다.
	private static long change(long basePrice) {
		long raw = Math.multiplyExact(basePrice, 30L) / 100;
		return raw - raw % tickSize(basePrice);
	}
}
