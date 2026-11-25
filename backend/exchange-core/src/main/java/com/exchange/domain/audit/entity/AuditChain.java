package com.exchange.domain.audit.entity;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 감사 로그 체인 한 칸. INSERT만 한다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditChain implements Persistable<Long> {

	@Id
	private Long chainSeq;

	private long auditId;

	private String prevHash;

	private String hash;

	/** 앞 칸에 이어지는 칸을 만든다. */
	public static AuditChain of(long chainSeq, long auditId, String prevHash, String hash) {
		AuditChain chain = new AuditChain();
		chain.chainSeq = chainSeq;
		chain.auditId = auditId;
		chain.prevHash = prevHash;
		chain.hash = hash;
		return chain;
	}

	@Override
	public Long getId() {
		return chainSeq;
	}

	/** 직접 번호를 매기므로 저장할 때 먼저 조회하지 않게 한다. */
	@Override
	public boolean isNew() {
		return true;
	}
}
