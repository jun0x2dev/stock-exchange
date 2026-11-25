package com.exchange.domain.audit.service;

/** 누가 어디서 무엇을 했고 결과가 어땠는지 남긴다. */
public interface AuditService {

	/** 업무 트랜잭션 안에서 성공을 남긴다. 업무가 롤백되면 함께 사라진다. */
	void recordSuccess(String action, String target);

	/** 업무 트랜잭션과 별개로 실패를 남긴다. */
	void recordFailure(String action, String result);

	/** 인증 정보가 아직 없는 로그인 결과를 남긴다. 없는 아이디면 {@code userId}는 null이다. */
	void recordLogin(Long userId, boolean success);
}
