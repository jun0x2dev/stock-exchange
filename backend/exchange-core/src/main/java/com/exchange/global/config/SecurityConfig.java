package com.exchange.global.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

import com.exchange.domain.audit.service.AuditService;
import com.exchange.domain.user.dto.LoginUser;
import com.exchange.domain.user.dto.MeResponse;
import com.exchange.domain.user.service.LoginService;
import com.exchange.global.error.ErrorCode;
import com.exchange.global.response.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 명시적으로 허용한 경로 외에는 모두 인증을 요구하는 보안 설정. */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	/** 화면 파일과 화면 주소. 데이터는 모두 인증이 필요한 API로만 나간다. */
	static final String[] PAGES = { "/login", "/stocks/{symbol}", "/account" };

	private static final String CSP = "default-src 'self'; img-src 'self' data:; object-src 'none'; "
		+ "base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

	private final JsonMapper jsonMapper;
	private final AuditService auditService;

	/** Argon2id로 비밀번호를 해시한다. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
	}

	/** 경로별 접근 권한, 세션 로그인, CSRF 방어, 인증·인가 실패 응답을 설정한다. */
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, LoginService loginService) throws Exception {
		return http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health").permitAll()
				.requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.svg", "/assets/**").permitAll()
				.requestMatchers(HttpMethod.GET, PAGES).permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/stock-deposits").hasRole("OPERATOR")
				.requestMatchers("/api/v1/admin/audit/**").hasRole("AUDITOR")
				.requestMatchers(HttpMethod.POST, "/api/v1/admin/adjustments").hasRole("OPERATOR")
				.requestMatchers(HttpMethod.POST, "/api/v1/admin/adjustments/**").hasRole("APPROVER")
				.requestMatchers(HttpMethod.GET, "/api/v1/admin/adjustments").hasAnyRole("OPERATOR", "APPROVER", "AUDITOR")
				.requestMatchers("/api/v1/admin/**").denyAll()
				.anyRequest().authenticated())
			.csrf(csrf -> csrf.spa())
			.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CSP)))
			// 세션 고정 방어와 CSRF 토큰 교체를 프레임워크에 맡기려고 폼 로그인을 쓴다.
			.formLogin(login -> login
				.loginPage("/login")
				.loginProcessingUrl("/api/v1/auth/login")
				.usernameParameter("loginId")
				.passwordParameter("password")
				.successHandler((request, response, authentication) -> {
					LoginUser user = (LoginUser) authentication.getPrincipal();
					loginService.onSuccess(user);
					write(response, 200, ApiResponse.ok(MeResponse.from(user)));
				})
				.failureHandler((request, response, e) -> {
					loginService.onFailure(request.getParameter("loginId"), e instanceof LockedException);
					fail(response, ErrorCode.LOGIN_FAILED);
				}))
			.logout(logout -> logout
				.logoutUrl("/api/v1/auth/logout")
				.logoutSuccessHandler((request, response, authentication) ->
					write(response, 200, ApiResponse.ok(null))))
			// 로그인 전 요청을 세션에 저장하면 인증 없이 세션을 계속 만들 수 있다.
			.requestCache(cache -> cache.requestCache(new NullRequestCache()))
			// 기본 예외 처리는 본문 없이 응답하므로 공통 응답 형식으로 맞춘다.
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint((request, response, e) -> fail(response, ErrorCode.UNAUTHORIZED))
				.accessDeniedHandler((request, response, e) -> {
					recordDenied(request);
					fail(response, ErrorCode.FORBIDDEN);
				}))
			.build();
	}

	/** 로그인한 사용자가 권한 없는 관리 기능을 시도하면 감사 로그에 남긴다. */
	private void recordDenied(HttpServletRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof LoginUser
				&& request.getRequestURI().startsWith("/api/v1/admin/")) {
			auditService.recordFailure(request.getMethod() + " " + request.getRequestURI(), ErrorCode.FORBIDDEN.name());
		}
	}

	/** 에러 코드를 공통 응답 형식으로 쓴다. */
	private void fail(HttpServletResponse response, ErrorCode code) throws IOException {
		write(response, code.getStatus().value(), ApiResponse.fail(code));
	}

	/** 응답 본문을 JSON으로 쓴다. */
	private void write(HttpServletResponse response, int status, ApiResponse<?> body) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getWriter(), body);
	}
}
