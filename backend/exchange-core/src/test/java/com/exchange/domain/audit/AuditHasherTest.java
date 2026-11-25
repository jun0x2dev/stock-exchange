package com.exchange.domain.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.exchange.domain.audit.service.impl.AuditHasher;

/** 감사 로그 키 검사. */
class AuditHasherTest {

	@Test
	@DisplayName("키가 없거나 짧으면 기동하지 않는다")
	void rejectsWeakKey() {
		assertThatThrownBy(() -> new AuditHasher("")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AuditHasher(Base64.getEncoder().encodeToString(new byte[31])))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
