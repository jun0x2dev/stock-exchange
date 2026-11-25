package com.exchange.domain.execution.service;

import java.util.Set;

/** 종목의 호가나 체결이 바뀌었음을 알리는 이벤트. 커밋된 뒤에만 전달된다. 사용자는 잔고나 주문이 바뀐 사람이다. */
public record Settled(String symbol, Set<Long> userIds) {
}
