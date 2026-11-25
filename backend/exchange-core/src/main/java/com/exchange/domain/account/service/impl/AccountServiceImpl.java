package com.exchange.domain.account.service.impl;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.account.dto.AccountResponse;
import com.exchange.domain.account.dto.DepositRequest;
import com.exchange.domain.account.dto.DepositResponse;
import com.exchange.domain.account.dto.PositionResponse;
import com.exchange.domain.account.dto.StockDepositRequest;
import com.exchange.domain.account.entity.Deposit;
import com.exchange.domain.account.exception.AccountException;
import com.exchange.domain.account.repository.AccountRepository;
import com.exchange.domain.account.repository.DepositRepository;
import com.exchange.domain.account.repository.PositionRepository;
import com.exchange.domain.account.service.AccountAccess;
import com.exchange.domain.account.service.AccountService;
import com.exchange.domain.audit.service.AuditService;
import com.exchange.domain.ledger.entity.LedgerRef;
import com.exchange.domain.ledger.entity.LedgerType;
import com.exchange.domain.ledger.entity.RefType;
import com.exchange.domain.ledger.service.LedgerService;
import com.exchange.domain.stock.dto.StockResponse;
import com.exchange.domain.stock.entity.Stock;
import com.exchange.domain.stock.service.StockService;
import com.exchange.global.error.ErrorCode;
import com.exchange.global.util.RequestHash;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 계좌 서비스 구현. 잔고 변경과 원장 기록을 한 트랜잭션에서 한다. */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

	private final AccountRepository accountRepository;
	private final PositionRepository positionRepository;
	private final DepositRepository depositRepository;
	private final AccountAccess accountAccess;
	private final StockService stockService;
	private final LedgerService ledgerService;
	private final AuditService auditService;

	@Override
	public List<AccountResponse> findMine(long userId) {
		return accountRepository.findByUserIdOrderByAccountId(userId).stream().map(AccountResponse::from).toList();
	}

	@Override
	public AccountResponse get(long userId, long accountId) {
		accountAccess.requireOwned(userId, accountId);
		return accountRepository.findById(accountId).map(AccountResponse::from)
			.orElseThrow(() -> new AccountException(ErrorCode.ACCOUNT_NOT_FOUND));
	}

	@Override
	public List<PositionResponse> findPositions(long userId, long accountId) {
		accountAccess.requireOwned(userId, accountId);
		Map<String, StockResponse> stocks = stockService.findAll().stream()
			.collect(Collectors.toMap(StockResponse::symbol, Function.identity()));
		return positionRepository.findByAccountId(accountId).stream()
			// 체결 전에 만들어 둔 빈 행은 보여 주지 않는다.
			.filter(p -> p.getQuantity() > 0 || p.getRealizedPnl() != 0)
			.map(p -> PositionResponse.of(p, stocks.get(p.getId().symbol())))
			.toList();
	}

	@Override
	@Transactional
	public DepositResponse deposit(long userId, long accountId, String idempotencyKey, DepositRequest request) {
		accountAccess.requireOwned(userId, accountId);
		long amount = request.amount();
		String hash = RequestHash.of("CASH", amount);
		Long depositId = depositRepository.insertIfAbsent(accountId, null, amount, idempotencyKey, hash).orElse(null);
		if (depositId == null) {
			return replay(accountId, idempotencyKey, hash);
		}
		if (accountRepository.deposit(accountId, amount) != 1) {
			throw new AccountException(ErrorCode.ACCOUNT_NOT_FOUND);
		}
		LedgerRef ref = ledgerService.open(LedgerType.DEPOSIT, RefType.DEPOSIT, depositId);
		ledgerService.cash(ref, accountId, amount, 0);
		ledgerService.cash(ref, accountRepository.findExternalId(), -amount, 0);
		auditService.recordSuccess("DEPOSIT", "deposit=" + depositId);
		log.info("입금: accountId={}, depositId={}", accountId, depositId);
		return new DepositResponse(depositId);
	}

	@Override
	@Transactional
	public DepositResponse depositStock(long accountId, String idempotencyKey, StockDepositRequest request) {
		if (!accountRepository.isCustomer(accountId)) {
			throw new AccountException(ErrorCode.ACCOUNT_NOT_FOUND);
		}
		Stock stock = stockService.get(request.symbol());
		String symbol = stock.getSymbol();
		long quantity = request.quantity();
		String hash = RequestHash.of("STOCK", symbol, quantity);
		Long depositId = depositRepository.insertIfAbsent(accountId, symbol, quantity, idempotencyKey, hash).orElse(null);
		if (depositId == null) {
			return replay(accountId, idempotencyKey, hash);
		}
		// 입고한 주식의 매입 원가는 기준가로 본다 (프로젝트 정책).
		long cost = Math.multiplyExact(quantity, stock.getBasePrice());
		positionRepository.insertIfAbsent(accountId, symbol);
		positionRepository.deposit(accountId, symbol, quantity, cost);
		LedgerRef ref = ledgerService.open(LedgerType.DEPOSIT, RefType.DEPOSIT, depositId);
		ledgerService.stock(ref, accountId, symbol, quantity, 0);
		ledgerService.stock(ref, accountRepository.findExternalId(), symbol, -quantity, 0);
		auditService.recordSuccess("STOCK_DEPOSIT", "deposit=" + depositId);
		log.info("주식 입고: accountId={}, depositId={}", accountId, depositId);
		return new DepositResponse(depositId);
	}

	// 같은 멱등키의 이전 결과를 돌려준다. 본문이 다르면 거부한다.
	private DepositResponse replay(long accountId, String idempotencyKey, String hash) {
		Deposit deposit = depositRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey)
			.filter(d -> d.getRequestHash().equals(hash))
			.orElseThrow(() -> new AccountException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
		return new DepositResponse(deposit.getDepositId());
	}
}
