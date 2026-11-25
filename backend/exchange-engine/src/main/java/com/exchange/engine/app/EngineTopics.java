package com.exchange.engine.app;

/** 엔진이 쓰는 Kafka 토픽. 두 토픽 모두 종목을 키로 써서 종목별 순서를 지킨다. */
public final class EngineTopics {

	/** core가 보내는 주문·취소 명령. 처음부터 다시 읽어 호가창을 복원하므로 지우지 않는다. */
	public static final String COMMANDS = "order-command";

	/** 엔진이 내보내는 처리 결과. */
	public static final String RESULTS = "engine-event";

	private EngineTopics() {
	}
}
