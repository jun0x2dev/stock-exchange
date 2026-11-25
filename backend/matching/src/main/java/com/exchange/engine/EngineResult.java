package com.exchange.engine;

import java.util.List;

/** 명령 하나의 처리 결과. 이벤트 순번은 이어지며, 함께 반영하거나 함께 버려야 한다. */
public record EngineResult(String symbol, long commandId, List<Event> events) {

	/** 전송용 이벤트. 종류에 맞는 필드만 값이 있다. */
	public record Event(Type type, long seq, long orderId, long buyOrderId, long sellOrderId, long price,
			long quantity) {

		/** 이벤트의 종류. */
		public enum Type {
			ACCEPTED, TRADED, CANCELED
		}

		/** 엔진 이벤트를 전송용으로 바꾼다. */
		public static Event from(EngineEvent event) {
			return switch (event) {
				case EngineEvent.Accepted e -> new Event(Type.ACCEPTED, e.seq(), e.orderId(), 0, 0, 0, 0);
				case EngineEvent.Traded e ->
					new Event(Type.TRADED, e.seq(), 0, e.buyOrderId(), e.sellOrderId(), e.price(), e.quantity());
				case EngineEvent.Canceled e -> new Event(Type.CANCELED, e.seq(), e.orderId(), 0, 0, 0, 0);
			};
		}

		/** 전송용 이벤트를 엔진 이벤트로 되돌린다. */
		public EngineEvent toEngineEvent() {
			return switch (type) {
				case ACCEPTED -> new EngineEvent.Accepted(seq, orderId);
				case TRADED -> new EngineEvent.Traded(seq, buyOrderId, sellOrderId, price, quantity);
				case CANCELED -> new EngineEvent.Canceled(seq, orderId);
			};
		}
	}

	/** 엔진이 낸 이벤트들로 결과를 만든다. */
	public static EngineResult of(EngineCommand command, List<EngineEvent> events) {
		return new EngineResult(command.symbol(), command.commandId(), events.stream().map(Event::from).toList());
	}

	/** 엔진 이벤트 목록. */
	public List<EngineEvent> toEngineEvents() {
		return events.stream().map(Event::toEngineEvent).toList();
	}
}
