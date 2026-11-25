package com.exchange.market;

/** 참고 시세. 호가 봇이 따라가는 가격이며 우리 거래소의 체결가가 아니다. */
public record Quote(String symbol, long price) {
}
