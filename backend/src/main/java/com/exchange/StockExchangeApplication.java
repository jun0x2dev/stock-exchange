package com.exchange;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/** 모의 증권 거래 시스템의 실행 진입점. */
// 기본 사용자가 생성되고 비밀번호가 로그에 출력되는 것을 막는다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class StockExchangeApplication {

	/** 애플리케이션을 실행한다. */
	public static void main(String[] args) {
		SpringApplication.run(StockExchangeApplication.class, args);
	}
}
