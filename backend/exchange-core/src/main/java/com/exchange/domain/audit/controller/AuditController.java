package com.exchange.domain.audit.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.exchange.domain.audit.dto.AuditVerification;
import com.exchange.domain.audit.service.AuditChainService;
import com.exchange.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/** 감사 로그 검증 API. 감사 담당자만 쓴다. */
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AuditController {

	private final AuditChainService chainService;

	/** 감사 로그가 기록된 뒤 바뀌거나 지워지지 않았는지 검사한다. */
	@GetMapping("/verification")
	ApiResponse<AuditVerification> verify() {
		return ApiResponse.ok(chainService.verify());
	}
}
