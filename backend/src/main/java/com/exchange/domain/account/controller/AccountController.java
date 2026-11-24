package com.exchange.domain.account.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.exchange.domain.account.dto.AccountResponse;
import com.exchange.domain.account.dto.DepositRequest;
import com.exchange.domain.account.dto.DepositResponse;
import com.exchange.domain.account.dto.PositionResponse;
import com.exchange.domain.account.dto.StockDepositRequest;
import com.exchange.domain.account.service.AccountService;
import com.exchange.domain.ledger.dto.LedgerResponse;
import com.exchange.domain.ledger.service.LedgerService;
import com.exchange.domain.user.dto.LoginUser;
import com.exchange.global.response.ApiResponse;
import com.exchange.global.response.CursorPage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** 계좌 API. 소유자 검증은 서비스에서 한다. */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

	private final AccountService accountService;
	private final LedgerService ledgerService;

	/** 내 계좌 목록. */
	@GetMapping
	ApiResponse<List<AccountResponse>> mine(@AuthenticationPrincipal LoginUser user) {
		return ApiResponse.ok(accountService.findMine(user.userId()));
	}

	/** 예수금과 주문 가능 금액. */
	@GetMapping("/{accountId}")
	ApiResponse<AccountResponse> get(@AuthenticationPrincipal LoginUser user, @PathVariable long accountId) {
		return ApiResponse.ok(accountService.get(user.userId(), accountId));
	}

	/** 보유 종목과 손익. */
	@GetMapping("/{accountId}/positions")
	ApiResponse<List<PositionResponse>> positions(@AuthenticationPrincipal LoginUser user,
			@PathVariable long accountId) {
		return ApiResponse.ok(accountService.findPositions(user.userId(), accountId));
	}

	/** 현금 원장. */
	@GetMapping("/{accountId}/ledgers")
	ApiResponse<CursorPage<LedgerResponse>> ledgers(@AuthenticationPrincipal LoginUser user,
			@PathVariable long accountId, @RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ApiResponse.ok(ledgerService.findCash(user.userId(), accountId, cursor, size));
	}

	/** 모의 입금. */
	@PostMapping("/{accountId}/deposits")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<DepositResponse> deposit(@AuthenticationPrincipal LoginUser user, @PathVariable long accountId,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
			@Valid @RequestBody DepositRequest request) {
		return ApiResponse.ok(accountService.deposit(user.userId(), accountId, idempotencyKey, request));
	}

	/** 모의 주식 입고. 운영자만 호출할 수 있다. */
	@PostMapping("/{accountId}/stock-deposits")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<DepositResponse> depositStock(@PathVariable long accountId,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
			@Valid @RequestBody StockDepositRequest request) {
		return ApiResponse.ok(accountService.depositStock(accountId, idempotencyKey, request));
	}
}
