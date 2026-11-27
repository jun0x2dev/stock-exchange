package com.exchange.domain.fds.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.fds.dto.FdsAlertResponse;
import com.exchange.domain.fds.repository.FdsAlertRepository;
import com.exchange.domain.fds.service.FdsService;
import com.exchange.global.response.CursorPage;

import lombok.extern.slf4j.Slf4j;

/** 이상거래 탐지 구현. 기준값은 설정(exchange.fds)에서 받는다. */
@Slf4j
@Service
@Transactional(readOnly = true)
public class FdsServiceImpl implements FdsService {

	private final FdsAlertRepository alertRepository;
	private final Spoofing spoofing;

	/** 허수성 호가 기준값. 모두 프로젝트 정책이며 거래소 규정이 아니다. */
	record Spoofing(int lookbackMinutes, int maxLifetimeSeconds, int maxFillPercent, int fakeToReal,
			int minDepthSharePercent) {
	}

	/** 저장소와 기준값을 받는다. */
	public FdsServiceImpl(FdsAlertRepository alertRepository,
			@Value("${exchange.fds.spoofing.lookback-minutes}") int lookbackMinutes,
			@Value("${exchange.fds.spoofing.max-lifetime-seconds}") int maxLifetimeSeconds,
			@Value("${exchange.fds.spoofing.max-fill-percent}") int maxFillPercent,
			@Value("${exchange.fds.spoofing.fake-to-real}") int fakeToReal,
			@Value("${exchange.fds.spoofing.min-depth-share-percent}") int minDepthSharePercent) {
		this.alertRepository = alertRepository;
		this.spoofing = new Spoofing(lookbackMinutes, maxLifetimeSeconds, maxFillPercent, fakeToReal,
			minDepthSharePercent);
	}

	@Override
	@Transactional
	public int detect() {
		int found = alertRepository.detectSpoofing(spoofing.lookbackMinutes(), spoofing.maxLifetimeSeconds(),
			spoofing.maxFillPercent(), spoofing.fakeToReal(), spoofing.minDepthSharePercent());
		if (found > 0) {
			log.warn("이상거래 경보: rule=SPOOFING, count={}", found);
		}
		return found;
	}

	@Override
	public CursorPage<FdsAlertResponse> find(Long cursor, int size) {
		List<FdsAlertResponse> rows = alertRepository.findRows(cursor == null ? Long.MAX_VALUE : cursor, Limit.of(size + 1))
			.stream().map(row -> FdsAlertResponse.of(row.alert(), row.owner())).toList();
		return CursorPage.of(rows, size, FdsAlertResponse::alertId);
	}
}
