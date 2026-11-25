package com.exchange.domain.audit.service.impl;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.temporal.ChronoUnit;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.exchange.domain.audit.entity.AuditLog;

/**
 * 감사 로그 한 건과 앞 칸의 해시로 HMAC-SHA256을 계산한다.
 *
 * <p>키를 DB 밖에 두므로 DB를 마음대로 고칠 수 있는 사람도 체인을 다시 만들 수 없다.
 */
@Component
public class AuditHasher {

	/** 첫 칸이 이어받는 값. */
	static final String GENESIS = "0".repeat(64);

	private static final int MIN_KEY_BYTES = 32;

	private final SecretKeySpec key;

	/** Base64로 된 32바이트 이상의 키를 받는다. */
	public AuditHasher(@Value("${exchange.audit.key}") String key) {
		Assert.hasText(key, "EXCHANGE_AUDIT_KEY를 설정해야 한다");
		byte[] bytes = Base64.getDecoder().decode(key);
		Assert.isTrue(bytes.length >= MIN_KEY_BYTES, "감사 로그 키는 32바이트 이상이어야 한다");
		this.key = new SecretKeySpec(bytes, "HmacSHA256");
	}

	/** 앞 칸의 해시와 로그 내용으로 해시를 만든다. */
	public String hash(String prevHash, AuditLog log) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return HexFormat.of().formatHex(mac.doFinal(encode(prevHash, log.getAuditId(), log.getUserId(), log.getIp(),
				log.getAction(), log.getTarget(), log.getResult(),
				ChronoUnit.MICROS.between(Instant.EPOCH, log.getCreatedAt()))));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	// 값마다 길이를 앞에 붙여, 이어 붙인 결과가 같은 서로 다른 로그가 생기지 않게 한다. null은 길이 -1이다.
	private static byte[] encode(Object... values) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Object value : values) {
			byte[] bytes = value == null ? new byte[0] : value.toString().getBytes(StandardCharsets.UTF_8);
			out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value == null ? -1 : bytes.length).array());
			out.writeBytes(bytes);
		}
		return out.toByteArray();
	}
}
