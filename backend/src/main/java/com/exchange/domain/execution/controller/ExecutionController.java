package com.exchange.domain.execution.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.exchange.domain.execution.dto.ExecutionResponse;
import com.exchange.domain.execution.service.ExecutionService;
import com.exchange.domain.user.dto.LoginUser;
import com.exchange.global.response.ApiResponse;
import com.exchange.global.response.CursorPage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** 내 체결 내역 API. */
@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

	private final ExecutionService executionService;

	/** 내 계좌의 체결 내역. */
	@GetMapping
	ApiResponse<CursorPage<ExecutionResponse>> find(@AuthenticationPrincipal LoginUser user,
			@RequestParam long accountId, @RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ApiResponse.ok(executionService.findMine(user.userId(), accountId, cursor, size));
	}
}
