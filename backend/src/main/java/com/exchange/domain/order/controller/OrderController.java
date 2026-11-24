package com.exchange.domain.order.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.exchange.domain.order.dto.OrderResponse;
import com.exchange.domain.order.dto.PlaceOrderRequest;
import com.exchange.domain.order.service.OrderService;
import com.exchange.domain.user.dto.LoginUser;
import com.exchange.global.response.ApiResponse;
import com.exchange.global.response.CursorPage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** 주문 API. 소유자 검증은 서비스에서 한다. */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

	private final OrderService orderService;

	/** 주문 접수. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<OrderResponse> place(@AuthenticationPrincipal LoginUser user,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
			@Valid @RequestBody PlaceOrderRequest request) {
		return ApiResponse.ok(orderService.place(user.userId(), idempotencyKey, request));
	}

	/** 주문 취소 요청. 결과는 주문 조회로 확인한다. */
	@DeleteMapping("/{orderId}")
	@ResponseStatus(HttpStatus.ACCEPTED)
	ApiResponse<OrderResponse> cancel(@AuthenticationPrincipal LoginUser user, @PathVariable long orderId) {
		return ApiResponse.ok(orderService.cancel(user.userId(), orderId));
	}

	/** 주문 한 건. */
	@GetMapping("/{orderId}")
	ApiResponse<OrderResponse> get(@AuthenticationPrincipal LoginUser user, @PathVariable long orderId) {
		return ApiResponse.ok(orderService.get(user.userId(), orderId));
	}

	/** 주문 내역. {@code active=true}면 미체결 주문만 조회한다. */
	@GetMapping
	ApiResponse<CursorPage<OrderResponse>> find(@AuthenticationPrincipal LoginUser user, @RequestParam long accountId,
			@RequestParam(defaultValue = "false") boolean active, @RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ApiResponse.ok(orderService.find(user.userId(), accountId, active, cursor, size));
	}
}
