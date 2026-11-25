package com.exchange.domain.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.exchange.IntegrationTest;
import com.exchange.engine.Side;

/** 실시간 연결의 내용과 접근 통제를 실제 서버에서 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeTest extends IntegrationTest {

	private final HttpClient http = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@Test
	@DisplayName("연결하면 호가를 받고, 체결되면 새 호가와 체결을 받는다")
	void pushesMarket() throws Exception {
		String symbol = stock();
		Trader seller = trader(0);
		giveStock(seller, symbol, 5);
		Trader buyer = trader(1_000_000);
		Client client = connect(login(trader(0)).cookie(), symbol, null);
		assertThat(client.next()).contains("\"type\":\"MARKET\"", "\"asks\":[]");
		place(seller, symbol, Side.SELL, 71_000, 5);
		assertThat(client.next()).contains("\"asks\":[{\"price\":71000,\"quantity\":5}]");
		place(buyer, symbol, Side.BUY, 71_000, 2);
		assertThat(client.next()).contains("\"quantity\":3", "\"trades\":[{\"seq\":3,\"price\":71000,\"quantity\":2");
		client.session.close();
	}

	@Test
	@DisplayName("내 주문이 바뀌면 알림을 받고, 남의 주문으로는 받지 않는다")
	void notifiesOnlyOwner() throws Exception {
		String symbol = stock();
		Trader owner = trader(1_000_000);
		Client mine = connect(login(owner).cookie(), symbol, null);
		Client other = connect(login(trader(0)).cookie(), symbol, null);
		mine.next();
		other.next();
		place(owner, symbol, Side.BUY, 71_000, 1);
		assertThat(mine.next() + mine.next()).contains("\"type\":\"MARKET\"", "\"type\":\"ACCOUNT\"");
		assertThat(other.next()).contains("\"type\":\"MARKET\"").doesNotContain("accountId", "userId", "orderId");
		assertThat(other.messages.poll(1, TimeUnit.SECONDS)).isNull();
		mine.session.close();
		other.session.close();
	}

	@Test
	@DisplayName("로그인하지 않거나 다른 출처에서는 연결할 수 없다")
	void rejectsUnauthorized() throws Exception {
		String symbol = stock();
		assertThatThrownBy(() -> connect("", symbol, null)).isInstanceOf(Exception.class);
		String cookie = login(trader(0)).cookie();
		assertThatThrownBy(() -> connect(cookie, symbol, "http://evil.example")).isInstanceOf(Exception.class);
		Client allowed = connect(cookie, symbol, "http://localhost:" + port);
		assertThat(allowed.next()).contains("MARKET");
		allowed.session.close();
	}

	@Test
	@DisplayName("로그아웃하면 연결이 끊긴다")
	void closesOnLogout() throws Exception {
		Login login = login(trader(0));
		Client client = connect(login.cookie(), stock(), null);
		client.next();
		http.send(HttpRequest.newBuilder(uri("/api/v1/auth/logout")).header("Cookie", login.csrf() + "; " + login.cookie())
			.header("X-XSRF-TOKEN", login.csrf().substring("XSRF-TOKEN=".length()))
			.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
		assertThat(client.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
	}

	@Test
	@DisplayName("화면에서 보낸 메시지는 받지 않고, 한 사용자가 여섯 번째로 연결하면 끊는다")
	void limitsClient() throws Exception {
		String symbol = stock();
		String cookie = login(trader(0)).cookie();
		Client first = connect(cookie, symbol, null);
		first.next();
		first.session.sendMessage(new TextMessage("hello"));
		assertThat(first.closed.get(5, TimeUnit.SECONDS)).isNotNull();
		for (int i = 0; i < 5; i++) {
			connect(cookie, symbol, null).next();
		}
		Client sixth = connect(cookie, symbol, null);
		assertThat(sixth.closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
	}

	@Test
	@DisplayName("없는 종목으로 연결하면 끊긴다")
	void closesUnknownSymbol() throws Exception {
		Client client = connect(login(trader(0)).cookie(), "NONE", null);
		assertThat(client.closed.get(5, TimeUnit.SECONDS)).isNotNull();
	}

	// 로그인하고 세션 쿠키와 CSRF 쿠키를 받는다.
	private Login login(Trader trader) throws Exception {
		String csrf = cookie(http.send(HttpRequest.newBuilder(uri("/api/v1/auth/me")).build(),
			HttpResponse.BodyHandlers.discarding()), "XSRF-TOKEN");
		HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
			.header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", csrf)
			.header("X-XSRF-TOKEN", csrf.substring("XSRF-TOKEN=".length()))
			.POST(HttpRequest.BodyPublishers.ofString("loginId=" + trader.loginId() + "&password="
				+ URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)))
			.build(), HttpResponse.BodyHandlers.discarding());
		assertThat(response.statusCode()).isEqualTo(200);
		return new Login(cookie(response, "JSESSIONID"), cookie(response, "XSRF-TOKEN"));
	}

	// 종목의 실시간 연결을 연다.
	private Client connect(String cookie, String symbol, String origin) throws Exception {
		Client client = new Client();
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.add("Cookie", cookie);
		if (origin != null) {
			headers.setOrigin(origin);
		}
		client.session = new StandardWebSocketClient()
			.execute(client, headers, URI.create("ws://localhost:" + port + "/ws/quotes/" + symbol))
			.get(5, TimeUnit.SECONDS);
		return client;
	}

	// 테스트 서버의 주소를 만든다.
	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	// 응답에서 마지막으로 설정한 쿠키의 이름과 값을 찾는다.
	private static String cookie(HttpResponse<?> response, String name) {
		return response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith(name + "="))
			.reduce((a, b) -> b).map(c -> c.split(";")[0]).orElseThrow();
	}

	/** 로그인 결과. */
	private record Login(String cookie, String csrf) {
	}

	/** 받은 메시지와 종료를 기록하는 연결. */
	private static final class Client extends TextWebSocketHandler {

		private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
		private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
		private WebSocketSession session;

		@Override
		public void afterConnectionEstablished(WebSocketSession opened) {
			opened.setTextMessageSizeLimit(1_000_000);
		}

		@Override
		protected void handleTextMessage(WebSocketSession from, TextMessage message) {
			messages.add(message.getPayload());
		}

		@Override
		public void afterConnectionClosed(WebSocketSession from, CloseStatus status) {
			closed.complete(status);
		}

		// 다음 메시지를 기다린다.
		private String next() throws InterruptedException {
			String message = messages.poll(10, TimeUnit.SECONDS);
			assertThat(message).as("10초 안에 메시지를 받아야 한다").isNotNull();
			return message;
		}
	}
}
