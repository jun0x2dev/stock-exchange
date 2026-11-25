package com.exchange.domain.order.service.impl;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.account.repository.AccountRepository;
import com.exchange.domain.account.repository.PositionRepository;
import com.exchange.domain.account.service.AccountAccess;
import com.exchange.domain.audit.service.AuditService;
import com.exchange.domain.ledger.entity.LedgerRef;
import com.exchange.domain.ledger.entity.LedgerType;
import com.exchange.domain.ledger.entity.RefType;
import com.exchange.domain.ledger.service.LedgerService;
import com.exchange.domain.order.dto.OrderResponse;
import com.exchange.domain.order.dto.PlaceOrderRequest;
import com.exchange.domain.order.entity.Order;
import com.exchange.domain.order.entity.OrderStatus;
import com.exchange.domain.order.exception.OrderException;
import com.exchange.domain.order.repository.OrderRepository;
import com.exchange.domain.order.service.OrderService;
import com.exchange.domain.outbox.service.OutboxService;
import com.exchange.domain.stock.entity.Stock;
import com.exchange.domain.stock.service.StockService;
import com.exchange.engine.BookSnapshot;
import com.exchange.engine.EngineCommand;
import com.exchange.engine.Side;
import com.exchange.global.error.ErrorCode;
import com.exchange.global.response.CursorPage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 주문 서비스 구현. 주문 저장, 예약, 원장 기록, 엔진에 보낼 명령 저장을 한 트랜잭션에서 한다. */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

	private static final int BOOK_DEPTH = 10;

	private final OrderRepository orderRepository;
	private final AccountRepository accountRepository;
	private final PositionRepository positionRepository;
	private final AccountAccess accountAccess;
	private final StockService stockService;
	private final LedgerService ledgerService;
	private final AuditService auditService;
	private final OutboxService outboxService;

	@Override
	@Transactional
	public OrderResponse place(long userId, String idempotencyKey, PlaceOrderRequest request) {
		long accountId = request.accountId();
		accountAccess.requireOwned(userId, accountId);

		// 재시도는 그 사이 종목이 정지되는 등 조건이 바뀌었더라도 처음 결과를 돌려준다.
		Optional<Order> existing = orderRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
		if (existing.isPresent()) {
			return replay(existing.get(), request);
		}
		stockService.get(request.symbol()).validateOrder(request.price());

		long reserved = request.side() == Side.BUY ? Order.reserveAmount(request.price(), request.quantity()) : 0;
		Long orderId = orderRepository.insertIfAbsent(accountId, request.symbol(), request.side().name(),
			request.price(), request.quantity(), reserved, idempotencyKey, request.hash()).orElse(null);
		if (orderId == null) {
			// 같은 키의 동시 요청이 먼저 커밋됐다.
			return replay(orderRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey)
				.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND)), request);
		}
		reserve(orderId, request, reserved);
		auditService.recordSuccess("PLACE_ORDER", "order=" + orderId);
		outboxService.append(EngineCommand.Type.PLACE, request.symbol(), orderId);
		log.info("주문 접수: orderId={}, accountId={}, symbol={}", orderId, accountId, request.symbol());
		return OrderResponse.from(orderRepository.findById(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND)));
	}

	@Override
	@Transactional
	public OrderResponse cancel(long userId, long orderId) {
		Order order = owned(userId, orderId);
		boolean requested = orderRepository.requestCancel(orderId) == 1;
		// 체결과 겹쳤을 수 있으므로 갱신 이후의 값을 다시 읽는다.
		order = owned(userId, orderId);
		if (!requested && !order.getStatus().isActive()) {
			throw new OrderException(ErrorCode.ORDER_NOT_CANCELABLE);
		}
		if (requested) {
			auditService.recordSuccess("CANCEL_ORDER", "order=" + orderId);
			outboxService.append(EngineCommand.Type.CANCEL, order.getSymbol(), orderId);
			log.info("취소 요청: orderId={}", orderId);
		}
		return OrderResponse.from(order);
	}

	@Override
	public OrderResponse get(long userId, long orderId) {
		return OrderResponse.from(owned(userId, orderId));
	}

	@Override
	public BookSnapshot book(String symbol) {
		Stock stock = stockService.get(symbol);
		// ponytail: 조회할 때마다 합산한다. 조회가 많아지면 결과를 반영할 때 만든 스냅샷을 쓴다.
		return new BookSnapshot(symbol, stock.getLastEventSeq(), levels(orderRepository.findBids(symbol, BOOK_DEPTH)),
			levels(orderRepository.findAsks(symbol, BOOK_DEPTH)));
	}

	@Override
	public CursorPage<OrderResponse> find(long userId, long accountId, boolean activeOnly, Long cursor, int size) {
		accountAccess.requireOwned(userId, accountId);
		Set<OrderStatus> statuses = activeOnly ? OrderStatus.ACTIVE : EnumSet.allOf(OrderStatus.class);
		List<OrderResponse> rows = orderRepository
			.findByAccountIdAndStatusInAndOrderIdLessThanOrderByOrderIdDesc(accountId, statuses,
				cursor == null ? Long.MAX_VALUE : cursor, Limit.of(size + 1))
			.stream().map(OrderResponse::from).toList();
		return CursorPage.of(rows, size, OrderResponse::orderId);
	}

	// 조회 결과를 호가창 줄로 바꾼다.
	private static List<BookSnapshot.Level> levels(List<OrderRepository.PriceLevel> rows) {
		return rows.stream().map(r -> new BookSnapshot.Level(r.getPrice(), r.getQuantity())).toList();
	}

	// 매수는 금액을, 매도는 수량을 예약하고 원장에 남긴다.
	private void reserve(long orderId, PlaceOrderRequest request, long amount) {
		long accountId = request.accountId();
		LedgerRef ref = ledgerService.open(LedgerType.RESERVE, RefType.ORDER, orderId);
		if (request.side() == Side.BUY) {
			if (accountRepository.reserve(accountId, amount) != 1) {
				throw new OrderException(ErrorCode.INSUFFICIENT_CASH);
			}
			ledgerService.cash(ref, accountId, 0, amount);
			return;
		}
		if (positionRepository.reserve(accountId, request.symbol(), request.quantity()) != 1) {
			throw new OrderException(ErrorCode.INSUFFICIENT_QUANTITY);
		}
		ledgerService.stock(ref, accountId, request.symbol(), 0, request.quantity());
	}

	// 같은 멱등키의 이전 주문을 돌려준다. 본문이 다르면 거부한다.
	private OrderResponse replay(Order order, PlaceOrderRequest request) {
		if (!order.getRequestHash().equals(request.hash())) {
			throw new OrderException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
		}
		return OrderResponse.from(order);
	}

	// 다른 사용자의 주문이면 존재 여부를 숨긴다.
	private Order owned(long userId, long orderId) {
		return orderRepository.findOwned(orderId, userId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
	}
}
