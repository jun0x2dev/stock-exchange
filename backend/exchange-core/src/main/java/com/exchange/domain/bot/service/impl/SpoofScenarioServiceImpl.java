package com.exchange.domain.bot.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.exchange.domain.account.repository.AccountRepository;
import com.exchange.domain.bot.service.SpoofScenarioService;
import com.exchange.domain.order.dto.PlaceOrderRequest;
import com.exchange.domain.order.entity.OrderType;
import com.exchange.domain.order.service.OrderService;
import com.exchange.domain.user.repository.UserRepository;
import com.exchange.engine.BookSnapshot;
import com.exchange.engine.Side;
import com.exchange.global.error.BusinessException;
import com.exchange.market.PricePolicy;

import lombok.RequiredArgsConstructor;

/** 공격 시나리오 구현. 일반 사용자와 같은 주문·취소 경로를 거쳐, 탐지가 실제 데이터에서 동작하는지 본다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SpoofScenarioServiceImpl implements SpoofScenarioService {

	/** 공격자 체험용 사용자. */
	static final String SPOOFER = "spoofer";

	/** 가짜 주문의 수량. 봇의 보통 호가(30~99주)보다 훨씬 크다. */
	static final long FAKE_QUANTITY = 1_000;

	/** 실제로 체결시키는 소량. */
	static final long REAL_QUANTITY = 3;

	private final UserRepository userRepository;
	private final AccountRepository accountRepository;
	private final OrderService orderService;

	// 가짜 주문과 실제 주문은 따로 커밋한다. 가짜 주문이 먼저 호가창에 있어야 그 사이의 체결로 남는다.
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Optional<Long> spoof(String symbol, Side fakeSide) {
		BookSnapshot book = orderService.book(symbol);
		List<BookSnapshot.Level> same = fakeSide == Side.BUY ? book.bids() : book.asks();
		if (same.isEmpty()) {
			return Optional.empty();
		}
		long best = same.getFirst().price();
		// 최우선 호가 한 칸 뒤에 둬 체결되지 않으면서 호가창을 두껍게 보이게 한다.
		long fakePrice = fakeSide == Side.BUY ? PricePolicy.below(best) : PricePolicy.above(best);
		Optional<Long> fake = place(symbol, fakeSide, fakePrice, FAKE_QUANTITY, "spoof-");
		// 반대 방향으로 최우선 호가를 받아 바로 체결시킨다. 가짜 주문보다 앞선 다른 사람의 주문과 만난다.
		fake.ifPresent(id -> place(symbol, fakeSide == Side.BUY ? Side.SELL : Side.BUY, best, REAL_QUANTITY, "gain-"));
		return fake;
	}

	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Optional<Long> decoy(String symbol, Side side) {
		BookSnapshot book = orderService.book(symbol);
		List<BookSnapshot.Level> same = side == Side.BUY ? book.bids() : book.asks();
		if (same.isEmpty()) {
			return Optional.empty();
		}
		long best = same.getFirst().price();
		long price = side == Side.BUY ? PricePolicy.below(PricePolicy.below(best)) : PricePolicy.above(PricePolicy.above(best));
		return place(symbol, side, price, FAKE_QUANTITY, "decoy-");
	}

	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void cancel(long orderId) {
		spoofer().ifPresent(trader -> {
			try {
				orderService.cancel(trader.userId(), orderId);
			} catch (BusinessException e) {
				// 그사이 모두 체결됐으면 취소할 것이 없다.
			}
		});
	}

	// 공격자 계좌로 주문한다. 계좌가 없거나 거부되면 비어 있다.
	private Optional<Long> place(String symbol, Side side, long price, long quantity, String label) {
		return spoofer().flatMap(trader -> {
			try {
				return Optional.of(orderService.place(trader.userId(), label + UUID.randomUUID(),
					new PlaceOrderRequest(trader.accountId(), symbol, side, OrderType.LIMIT, price, quantity)).orderId());
			} catch (BusinessException e) {
				return Optional.empty();
			}
		});
	}

	// 공격자 체험용 계좌. 체험용 데이터를 만들지 않았으면 없다.
	private Optional<Trader> spoofer() {
		return userRepository.findByLoginId(SPOOFER).flatMap(user -> accountRepository
			.findByUserIdOrderByAccountId(user.getUserId()).stream().findFirst()
			.map(account -> new Trader(user.getUserId(), account.getAccountId())));
	}

	/** 공격자의 사용자와 계좌. */
	private record Trader(long userId, long accountId) {
	}
}
