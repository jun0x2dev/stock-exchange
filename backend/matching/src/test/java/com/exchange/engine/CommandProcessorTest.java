package com.exchange.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 중복 제거와 재실행 결과를 검증한다. */
class CommandProcessorTest {

	@Test
	@DisplayName("같은 명령이 다시 와도 한 번만 처리한다")
	void ignoresDuplicateCommand() {
		CommandProcessor processor = new CommandProcessor();
		EngineCommand place = new EngineCommand(1, EngineCommand.Type.PLACE, "T", 10, 1, Side.BUY, 100, 5);
		assertThat(processor.process(place)).hasSize(1);
		assertThat(processor.process(place)).isEmpty();
		assertThat(processor.snapshot("T", 10).bids()).containsExactly(new BookSnapshot.Level(100, 5));
	}

	@Test
	@DisplayName("종목마다 순번을 따로 센다")
	void countsSequencePerSymbol() {
		CommandProcessor processor = new CommandProcessor();
		assertThat(processor.process(new EngineCommand(1, EngineCommand.Type.PLACE, "A", 10, 1, Side.BUY, 100, 5)))
			.containsExactly(new EngineEvent.Accepted(1, 10));
		assertThat(processor.process(new EngineCommand(2, EngineCommand.Type.PLACE, "B", 11, 1, Side.BUY, 100, 5)))
			.containsExactly(new EngineEvent.Accepted(1, 11));
	}

	@ParameterizedTest(name = "시드 {0}")
	@ValueSource(longs = { 3, 77, 2026 })
	@DisplayName("중복이 섞인 기록을 처음부터 다시 처리해도 결과와 호가창이 같다")
	void replayIsIdentical(long seed) {
		Random random = new Random(seed);
		List<EngineCommand> log = new ArrayList<>();
		for (long id = 1; id <= 5_000; id++) {
			String symbol = "S" + random.nextInt(3);
			log.add(random.nextInt(5) == 0
				? new EngineCommand(id, EngineCommand.Type.CANCEL, symbol, 1 + random.nextLong(id), 0, null, 0, 0)
				: new EngineCommand(id, EngineCommand.Type.PLACE, symbol, id, random.nextInt(6),
					random.nextBoolean() ? Side.BUY : Side.SELL, 95 + random.nextInt(11), 1 + random.nextInt(20)));
			if (random.nextInt(10) == 0) {
				// 발행기가 묶음을 다시 보낸 상황.
				log.addAll(List.copyOf(log.subList(Math.max(0, log.size() - 50), log.size())));
			}
		}
		CommandProcessor first = new CommandProcessor();
		CommandProcessor second = new CommandProcessor();
		List<List<EngineEvent>> a = log.stream().map(first::process).toList();
		List<List<EngineEvent>> b = log.stream().map(second::process).toList();
		assertThat(b).isEqualTo(a);
		for (String symbol : List.of("S0", "S1", "S2")) {
			assertThat(second.snapshot(symbol, 100)).isEqualTo(first.snapshot(symbol, 100));
		}
	}
}
