package com.exchange.domain.account.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.exchange.domain.account.dto.DepositSummary;
import com.exchange.domain.account.service.AccountService;
import com.exchange.global.response.ApiResponse;
import com.exchange.global.response.CursorPage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** 운영자가 정정할 입금·입고를 찾는 API. 역할은 보안 설정이 검사한다. */
@RestController
@RequestMapping("/api/v1/admin/deposits")
@RequiredArgsConstructor
public class DepositAdminController {

	private final AccountService accountService;

	/** 모든 고객의 입금·입고를 최신순으로 조회한다. */
	@GetMapping
	ApiResponse<CursorPage<DepositSummary>> find(@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ApiResponse.ok(accountService.findDeposits(cursor, size));
	}
}
