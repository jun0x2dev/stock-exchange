package com.exchange.global.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

/** 같은 멱등키로 온 요청의 본문이 같은지 비교하는 해시. */
public final class RequestHash {

	private RequestHash() {
	}

	/** 필드를 정해진 순서로 이어 붙인 값의 SHA-256. 필드 값에는 구분자가 들어갈 수 없는 값만 쓴다. */
	public static String of(Object... fields) {
		String joined = Arrays.stream(fields).map(String::valueOf).collect(Collectors.joining("|"));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
