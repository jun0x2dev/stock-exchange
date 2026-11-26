package com.exchange.domain.reconciliation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.exchange.domain.reconciliation.dto.ReconciliationDetail;
import com.exchange.domain.reconciliation.dto.ReconciliationResponse;
import com.exchange.domain.reconciliation.service.ReconciliationService;
import com.exchange.domain.user.dto.LoginUser;
import com.exchange.global.response.ApiResponse;
import com.exchange.global.response.CursorPage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** 대사 API. 역할은 보안 설정이 검사한다. */
@RestController
@RequestMapping("/api/v1/admin/reconciliations")
@RequiredArgsConstructor
public class ReconciliationController {

	private final ReconciliationService reconciliationService;

	/** 지금 대사한다. 운영자와 감사 담당자가 한다. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<ReconciliationResponse> run(@AuthenticationPrincipal LoginUser user) {
		return ApiResponse.ok(reconciliationService.run(user.userId()));
	}

	/** 대사 이력. */
	@GetMapping
	ApiResponse<CursorPage<ReconciliationResponse>> find(@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ApiResponse.ok(reconciliationService.find(cursor, size));
	}

	/** 대사 결과와 불일치 내역. */
	@GetMapping("/{runId}")
	ApiResponse<ReconciliationDetail> get(@PathVariable long runId) {
		return ApiResponse.ok(reconciliationService.get(runId));
	}
}
