package com.exchange.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.web.servlet.MockMvc;

import com.exchange.TestcontainersConfiguration;

/** 실제 PostgreSQL에 연결해 애플리케이션 기동과 기본 접근 통제를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityConfigTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("상태 확인은 인증 없이 호출할 수 있고 상세 정보를 노출하지 않는다")
	void healthIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components").doesNotExist());
	}

	@Test
	@DisplayName("허용하지 않은 경로는 인증이 없으면 401 공통 응답을 반환한다")
	void unknownPathRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/orders"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("상태 확인 외의 actuator 경로는 인증이 없으면 막힌다")
	void otherActuatorEndpointsAreBlocked() throws Exception {
		mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("로그인했어도 CSRF 토큰이 없는 변경 요청은 403 공통 응답을 반환한다")
	void postWithoutCsrfIsForbidden() throws Exception {
		mockMvc.perform(post("/api/v1/orders").with(user("customer")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("기본 사용자가 자동 생성되지 않는다")
	void defaultUserIsNotCreated() {
		assertThat(context.getBeanNamesForType(InMemoryUserDetailsManager.class)).isEmpty();
	}
}
