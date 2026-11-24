package com.exchange.global.response;

import java.util.List;
import java.util.function.Function;

/** 커서 페이징 응답. 다음 페이지가 없으면 {@code nextCursor}는 null이다. */
public record CursorPage<T>(List<T> items, Long nextCursor) {

	/** 한 건 더 조회한 결과로 다음 페이지 유무를 판단한다. */
	public static <T> CursorPage<T> of(List<T> rows, int size, Function<T, Long> id) {
		if (rows.size() <= size) {
			return new CursorPage<>(rows, null);
		}
		List<T> items = List.copyOf(rows.subList(0, size));
		return new CursorPage<>(items, id.apply(items.getLast()));
	}
}
