package com.exchange.domain.stock.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.stock.dto.StockResponse;
import com.exchange.domain.stock.entity.Stock;
import com.exchange.domain.stock.entity.StockStatus;
import com.exchange.domain.stock.exception.StockException;
import com.exchange.domain.stock.repository.StockRepository;
import com.exchange.domain.stock.service.StockService;
import com.exchange.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

/** 종목 서비스 구현. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StockServiceImpl implements StockService {

	private final StockRepository stockRepository;

	@Override
	public List<StockResponse> findAll() {
		return stockRepository.findAllWithPrice().stream().map(StockResponse::from).toList();
	}

	@Override
	public Stock get(String symbol) {
		return stockRepository.findById(symbol)
			.orElseThrow(() -> new StockException(ErrorCode.STOCK_NOT_FOUND));
	}

	@Override
	@Transactional
	public void halt(String symbol) {
		stockRepository.updateStatus(symbol, StockStatus.HALTED);
	}
}
