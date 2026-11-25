package com.exchange.domain.adjustment.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.exchange.domain.adjustment.dto.AdjustmentRequest;
import com.exchange.domain.adjustment.dto.AdjustmentResponse;
import com.exchange.domain.adjustment.service.AdjustmentService;
import com.exchange.domain.user.dto.LoginUser;
import com.exchange.global.response.ApiResponse;
import com.exchange.global.response.CursorPage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** 원장 정정 API. 역할은 보안 설정이, 요청자와 승인자의 분리는 서비스와 DB가 검사한다. */
@RestController
@RequestMapping("/api/v1/admin/adjustments")
@RequiredArgsConstructor
public class AdjustmentController {

	private final AdjustmentService adjustmentService;

	/** 정정을 요청한다. 운영자만 한다. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<AdjustmentResponse> request(@AuthenticationPrincipal LoginUser user,
			@Valid @RequestBody AdjustmentRequest request) {
		return ApiResponse.ok(adjustmentService.request(user.userId(), request));
	}

	/** 승인한다. 승인자만 한다. */
	@PostMapping("/{adjustmentId}/approval")
	ApiResponse<AdjustmentResponse> approve(@AuthenticationPrincipal LoginUser user, @PathVariable long adjustmentId) {
		return ApiResponse.ok(adjustmentService.approve(user.userId(), adjustmentId));
	}

	/** 거절한다. 승인자만 한다. */
	@PostMapping("/{adjustmentId}/rejection")
	ApiResponse<AdjustmentResponse> reject(@AuthenticationPrincipal LoginUser user, @PathVariable long adjustmentId) {
		return ApiResponse.ok(adjustmentService.reject(user.userId(), adjustmentId));
	}

	/** 정정 요청 목록. */
	@GetMapping
	ApiResponse<CursorPage<AdjustmentResponse>> find(@RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ApiResponse.ok(adjustmentService.find(cursor, size));
	}
}
