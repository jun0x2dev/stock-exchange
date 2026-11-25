package com.exchange.domain.audit.dto;

import java.util.List;

/** 감사 로그 검증 결과. 문제가 하나도 없어야 정상이다. 문제는 앞에서부터 일부만 담는다. */
public record AuditVerification(boolean valid, long checked, long anchors, List<String> problems) {
}
