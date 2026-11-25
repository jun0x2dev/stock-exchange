package com.exchange.domain.stock.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.exchange.domain.execution.dto.TradeResponse;
import com.exchange.domain.execution.service.ExecutionService;
import com.exchange.domain.order.service.OrderService;
import com.exchange.domain.stock.dto.StockResponse;
import com.exchange.domain.stock.service.StockService;
import com.exchange.engine.BookSnapshot;
import com.exchange.global.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/** 종목, 호가창, 시세 조회 API. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StockController {

	private final StockService stockService;
	private final OrderService orderService;
	private final ExecutionService executionService;

	/** 종목 목록과 현재가. */
	@GetMapping("/stocks")
	ApiResponse<List<StockResponse>> stocks() {
		return ApiResponse.ok(stockService.findAll());
	}

	/** 종목의 최근 체결. 누구의 주문인지는 노출하지 않는다. */
	@GetMapping("/stocks/{symbol}/trades")
	ApiResponse<List<TradeResponse>> trades(@PathVariable String symbol) {
		return ApiResponse.ok(executionService.recentTrades(symbol));
	}

	/** 호가창 스냅샷. */
	@GetMapping("/orderbooks/{symbol}")
	ApiResponse<BookSnapshot> orderBook(@PathVariable String symbol) {
		return ApiResponse.ok(orderService.book(symbol));
	}
}
