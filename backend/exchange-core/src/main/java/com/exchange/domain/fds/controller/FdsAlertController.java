package com.exchange.domain.fds.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.exchange.domain.fds.dto.FdsAlertResponse;
import com.exchange.domain.fds.service.FdsService;
import com.exchange.global.response.ApiResponse;
import com.exchange.global.response.CursorPage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** 이상거래 경보 API. 역할은 보안 설정이 검사한다. */
@RestController
@RequestMapping("/api/v1/admin/fds-alerts")
@RequiredArgsConstructor
public class FdsAlertController {

	private final FdsService fdsService;

	/** 경보를 최신순으로 조회한다. */
	@GetMapping
	ApiResponse<CursorPage<FdsAlertResponse>> find(@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ApiResponse.ok(fdsService.find(cursor, size));
	}
}
