package com.exchange.global.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import com.exchange.global.error.ErrorCode;
import com.exchange.global.response.ApiResponse;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 명시적으로 허용한 경로 외에는 모두 인증을 요구하는 보안 설정. */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private final JsonMapper jsonMapper;

	/** 경로별 접근 권한과 인증·인가 실패 응답을 설정한다. CSRF 방어는 기본값(켜짐)을 유지한다. */
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health").permitAll()
				.anyRequest().authenticated())
			// 기본 예외 처리는 본문 없이 응답하므로 공통 응답 형식으로 맞춘다.
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint((request, response, e) -> write(response, ErrorCode.UNAUTHORIZED))
				.accessDeniedHandler((request, response, e) -> write(response, ErrorCode.FORBIDDEN)))
			.build();
	}

	/** 에러 코드를 공통 응답 형식의 JSON으로 쓴다. */
	private void write(HttpServletResponse response, ErrorCode code) throws IOException {
		response.setStatus(code.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getWriter(), ApiResponse.fail(code));
	}
}
