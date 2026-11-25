package com.exchange.domain.outbox.service;

/** 보낼 명령이 생겼음을 알리는 이벤트. 커밋된 뒤에만 전달된다. */
public record OutboxAppended() {
}
