package com.exchange.engine.app;

/** 매칭 엔진의 실행 진입점. */
public final class EngineApplication {

	private EngineApplication() {
	}

	/** 환경 변수 KAFKA_BOOTSTRAP_SERVERS의 Kafka에 연결해 엔진을 실행한다. */
	public static void main(String[] args) throws InterruptedException {
		EngineRunner runner = new EngineRunner(
			System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"));
		Runtime.getRuntime().addShutdownHook(new Thread(runner::close));
		runner.start();
		runner.join();
	}
}
