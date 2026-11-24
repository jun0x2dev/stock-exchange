package com.exchange.domain.account.entity;

import java.io.Serializable;

import jakarta.persistence.Embeddable;

/** 보유 종목의 식별자. */
@Embeddable
public record PositionId(Long accountId, String symbol) implements Serializable {
}
