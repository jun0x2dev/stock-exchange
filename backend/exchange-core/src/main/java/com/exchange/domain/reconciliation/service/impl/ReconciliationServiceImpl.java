package com.exchange.domain.reconciliation.service.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.audit.dto.AuditVerification;
import com.exchange.domain.audit.service.AuditChainService;
import com.exchange.domain.audit.service.AuditService;
import com.exchange.domain.reconciliation.dto.ReconciliationDetail;
import com.exchange.domain.reconciliation.dto.ReconciliationResponse;
import com.exchange.domain.reconciliation.entity.ReconciliationRun;
import com.exchange.domain.reconciliation.entity.ReconciliationStatus;
import com.exchange.domain.reconciliation.entity.ReconciliationTrigger;
import com.exchange.domain.reconciliation.exception.ReconciliationException;
import com.exchange.domain.reconciliation.repository.ReconciliationRepository;
import com.exchange.domain.reconciliation.service.ReconciliationService;
import com.exchange.global.error.ErrorCode;
import com.exchange.global.response.CursorPage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 대사 구현. 결과는 실행한 트랜잭션에서 함께 저장해, 실행했는데 기록이 없는 경우가 생기지 않는다. */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReconciliationServiceImpl implements ReconciliationService {

	private static final int DETAIL_LIMIT = 100;

	private final ReconciliationRepository reconciliationRepository;
	private final AuditChainService auditChainService;
	private final AuditService auditService;

	@Override
	@Transactional
	public ReconciliationResponse run(long userId) {
		if (!reconciliationRepository.tryLock()) {
			throw new ReconciliationException(ErrorCode.RECONCILIATION_RUNNING);
		}
		ReconciliationRun run = reconcile(ReconciliationTrigger.MANUAL, userId, 0);
		auditService.recordSuccess("RECONCILE", "reconciliation=" + run.getRunId());
		return ReconciliationResponse.from(run);
	}

	// 잠근 뒤에 이력을 확인해야, 동시에 실행한 서버가 같은 날을 두 번 대사하지 않는다.
	@Override
	@Transactional
	public Optional<ReconciliationResponse> runScheduled() {
		if (!reconciliationRepository.tryLock() || reconciliationRepository.existsScheduled(1)) {
			return Optional.empty();
		}
		return Optional.of(ReconciliationResponse.from(reconcile(ReconciliationTrigger.SCHEDULED, null, 1)));
	}

	@Override
	public CursorPage<ReconciliationResponse> find(Long cursor, int size) {
		List<ReconciliationResponse> rows = reconciliationRepository
			.findByRunIdLessThanOrderByRunIdDesc(cursor == null ? Long.MAX_VALUE : cursor, Limit.of(size + 1))
			.stream().map(ReconciliationResponse::from).toList();
		return CursorPage.of(rows, size, ReconciliationResponse::runId);
	}

	@Override
	public ReconciliationDetail get(long runId) {
		ReconciliationRun run = reconciliationRepository.findByRunId(runId)
			.orElseThrow(() -> new ReconciliationException(ErrorCode.RECONCILIATION_NOT_FOUND));
		return new ReconciliationDetail(ReconciliationResponse.from(run),
			reconciliationRepository.findViolations(runId, Limit.of(DETAIL_LIMIT)).stream()
				.map(ReconciliationDetail.Violation::from).toList());
	}

	// 감사 로그를 검증하고 불변식 대사 결과와 함께 저장한다. 불일치가 있으면 알린다.
	private ReconciliationRun reconcile(ReconciliationTrigger trigger, Long userId, int daysBack) {
		AuditVerification audit = auditChainService.verify();
		long runId = reconciliationRepository.record(trigger.name(), userId, daysBack, audit.problems().size(),
			audit.checked());
		audit.problems().forEach(problem -> reconciliationRepository.recordAuditProblem(runId, problem));
		ReconciliationRun run = reconciliationRepository.findByRunId(runId).orElseThrow();
		if (run.getStatus() == ReconciliationStatus.FAILED) {
			// ponytail: 로그로만 알린다. 운영이라면 메신저·메일 알림을 여기에 붙인다.
			log.error("대사 불일치: runId={}, date={}, invariants={}, audit={}", runId, run.getBusinessDate(),
				run.getInvariantViolations(), run.getAuditProblems());
		} else {
			log.info("대사 일치: runId={}, date={}, trigger={}", runId, run.getBusinessDate(), trigger);
		}
		return run;
	}
}
