package com.exchange.domain.audit.service;

import com.exchange.domain.audit.dto.AuditVerification;

/** 감사 로그를 체인으로 잇고 변조를 검사한다. */
public interface AuditChainService {

	/** 대기 중인 감사 로그를 체인에 넣고, 넣은 수를 반환한다. */
	int extend();

	/** 체인의 마지막 값을 DB 밖에 남긴다. 바뀐 것이 없으면 아무 일도 하지 않는다. */
	void anchor();

	/** 체인 전체와 DB 밖의 값을 다시 계산해 비교한다. */
	AuditVerification verify();
}
