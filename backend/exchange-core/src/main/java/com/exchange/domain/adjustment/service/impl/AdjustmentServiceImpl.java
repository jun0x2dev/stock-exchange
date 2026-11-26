package com.exchange.domain.adjustment.service.impl;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.account.service.AccountService;
import com.exchange.domain.adjustment.dto.AdjustmentRequest;
import com.exchange.domain.adjustment.dto.AdjustmentResponse;
import com.exchange.domain.adjustment.entity.AdjustmentStatus;
import com.exchange.domain.adjustment.exception.AdjustmentException;
import com.exchange.domain.adjustment.repository.LedgerAdjustmentRepository;
import com.exchange.domain.adjustment.service.AdjustmentService;
import com.exchange.domain.audit.service.AuditService;
import com.exchange.global.error.ErrorCode;
import com.exchange.global.response.CursorPage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 원장 정정 구현. 승인과 반대 전표는 한 트랜잭션이라 하나만 남을 수 없다. */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdjustmentServiceImpl implements AdjustmentService {

	private final LedgerAdjustmentRepository adjustmentRepository;
	private final AccountService accountService;
	private final AuditService auditService;

	@Override
	@Transactional
	public AdjustmentResponse request(long userId, AdjustmentRequest request) {
		accountService.requireDeposit(request.depositId());
		long adjustmentId = adjustmentRepository.insertIfAbsent(request.depositId(), request.reason(), userId)
			.orElseThrow(() -> new AdjustmentException(ErrorCode.ADJUSTMENT_ALREADY_REQUESTED));
		auditService.recordSuccess("REQUEST_ADJUSTMENT", "adjustment=" + adjustmentId);
		log.info("원장 정정 요청: adjustmentId={}, depositId={}", adjustmentId, request.depositId());
		return get(adjustmentId);
	}

	@Override
	@Transactional
	public AdjustmentResponse approve(long userId, long adjustmentId) {
		AdjustmentResponse decided = decide(userId, adjustmentId, AdjustmentStatus.APPROVED);
		accountService.reverseDeposit(decided.depositId(), adjustmentId);
		auditService.recordSuccess("APPROVE_ADJUSTMENT", "adjustment=" + adjustmentId);
		log.info("원장 정정 승인: adjustmentId={}", adjustmentId);
		return decided;
	}

	@Override
	@Transactional
	public AdjustmentResponse reject(long userId, long adjustmentId) {
		AdjustmentResponse decided = decide(userId, adjustmentId, AdjustmentStatus.REJECTED);
		auditService.recordSuccess("REJECT_ADJUSTMENT", "adjustment=" + adjustmentId);
		log.info("원장 정정 거절: adjustmentId={}", adjustmentId);
		return decided;
	}

	@Override
	public CursorPage<AdjustmentResponse> find(Long cursor, int size) {
		List<AdjustmentResponse> rows = adjustmentRepository
			.findRows(cursor == null ? Long.MAX_VALUE : cursor, Limit.of(size + 1))
			.stream().map(AdjustmentResponse::from).toList();
		return CursorPage.of(rows, size, AdjustmentResponse::adjustmentId);
	}

	// 상태를 바꾼다. 바꾸지 못했으면 이유를 찾아 알린다.
	private AdjustmentResponse decide(long userId, long adjustmentId, AdjustmentStatus status) {
		boolean decided = adjustmentRepository.decide(adjustmentId, status.name(), userId) == 1;
		LedgerAdjustmentRepository.Row row = adjustmentRepository.findRow(adjustmentId)
			.orElseThrow(() -> new AdjustmentException(ErrorCode.ADJUSTMENT_NOT_FOUND));
		if (decided) {
			return AdjustmentResponse.from(row);
		}
		throw new AdjustmentException(row.adjustment().getRequestedBy() == userId
			? ErrorCode.ADJUSTMENT_SELF_DECISION : ErrorCode.ADJUSTMENT_ALREADY_DECIDED);
	}

	// 한 건을 조회한다.
	private AdjustmentResponse get(long adjustmentId) {
		return adjustmentRepository.findRow(adjustmentId)
			.map(AdjustmentResponse::from)
			.orElseThrow(() -> new AdjustmentException(ErrorCode.ADJUSTMENT_NOT_FOUND));
	}
}
