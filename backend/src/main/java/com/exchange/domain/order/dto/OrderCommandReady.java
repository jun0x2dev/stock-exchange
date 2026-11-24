package com.exchange.domain.order.dto;

/** 종목에 엔진이 처리할 주문이나 취소가 생겼음을 알리는 이벤트. 커밋된 뒤에만 전달된다. */
public record OrderCommandReady(String symbol) {
}
