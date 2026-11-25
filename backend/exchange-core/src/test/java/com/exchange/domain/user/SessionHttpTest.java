package com.exchange.domain.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import com.exchange.IntegrationTest;

/** 쿠키와 세션 교체는 MockMvc가 흉내 내지 못하므로 실제 서버에 HTTP로 요청해 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionHttpTest extends IntegrationTest {

	private final HttpClient client = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Test
	@DisplayName("로그인 전 첫 조회에서 CSRF 토큰 쿠키를 받는다")
	void csrfCookieIsIssued() throws Exception {
		HttpResponse<String> response = me("");
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(cookie(response, "XSRF-TOKEN")).startsWith("XSRF-TOKEN=");
	}

	@Test
	@DisplayName("다시 로그인하면 세션 ID가 바뀌고 이전 ID는 쓸 수 없다")
	void sessionIdChangesOnLogin() throws Exception {
		Trader trader = trader(0);
		String first = cookie(login(trader, ""), "JSESSIONID");
		String second = cookie(login(trader, first), "JSESSIONID");
		assertThat(value(second)).isNotEqualTo(value(first));
		assertThat(me(value(first)).statusCode()).isEqualTo(401);
		assertThat(me(value(second)).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("공격자가 미리 정한 세션 ID를 받아들이지 않는다")
	void rejectsPresetSessionId() throws Exception {
		String preset = "JSESSIONID=0123456789ABCDEF0123456789ABCDEF";
		assertThat(value(cookie(login(trader(0), preset), "JSESSIONID"))).isNotEqualTo(preset);
		assertThat(me(preset).statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("세션 쿠키는 HttpOnly, Secure, SameSite=Strict다")
	void sessionCookieAttributes() throws Exception {
		assertThat(cookie(login(trader(0), ""), "JSESSIONID")).contains("HttpOnly", "Secure", "SameSite=Strict");
	}

	// 로그인 요청을 보낸다.
	private HttpResponse<String> login(Trader trader, String sessionCookie) throws Exception {
		String csrf = value(cookie(me(""), "XSRF-TOKEN"));
		HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Cookie", csrf + "; " + value(sessionCookie))
			.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()))
			.POST(HttpRequest.BodyPublishers.ofString("loginId=" + trader.loginId() + "&password="
				+ URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)))
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return response;
	}

	// 로그인 상태를 조회한다.
	private HttpResponse<String> me(String cookie) throws Exception {
		return client.send(HttpRequest.newBuilder(uri("/api/v1/auth/me")).header("Cookie", cookie).build(),
			HttpResponse.BodyHandlers.ofString());
	}

	// 테스트 서버의 주소를 만든다.
	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	// 응답에서 이름이 맞는 쿠키를 찾는다. 지우는 쿠키 다음에 새 쿠키가 오므로 마지막 것을 쓴다.
	private static String cookie(HttpResponse<String> response, String name) {
		List<String> cookies = response.headers().allValues("Set-Cookie").stream()
			.filter(c -> c.startsWith(name + "=")).toList();
		assertThat(cookies).isNotEmpty();
		return cookies.getLast();
	}

	// 쿠키의 이름과 값만 남긴다.
	private static String value(String cookie) {
		return cookie.split(";")[0];
	}
}
