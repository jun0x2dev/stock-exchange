package com.exchange.domain.realtime.handler;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import com.exchange.domain.execution.service.ExecutionService;
import com.exchange.domain.execution.service.Settled;
import com.exchange.domain.order.service.OrderService;
import com.exchange.domain.realtime.dto.RealtimeMessage;
import com.exchange.domain.user.dto.LoginUser;
import com.exchange.global.error.BusinessException;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 호가와 체결을 화면에 보낸다.
 *
 * <p>로그인한 세션만 연결할 수 있고, 로그아웃하거나 세션이 끝나면 연결도 끊는다. 화면에서 오는 메시지는 받지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeHandler extends TextWebSocketHandler {

	private static final int MAX_PER_USER = 5;
	private static final long FLUSH_MILLIS = 200;
	private static final int SEND_TIMEOUT_MILLIS = 2_000;
	private static final int SEND_BUFFER_BYTES = 256 * 1024;

	private final OrderService orderService;
	private final ExecutionService executionService;
	private final JsonMapper jsonMapper;
	private final Map<String, Client> clients = new ConcurrentHashMap<>();
	private final Set<String> dirtySymbols = ConcurrentHashMap.newKeySet();
	private final Set<Long> dirtyUsers = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean scheduled = new AtomicBoolean();
	private final ScheduledExecutorService worker =
		Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "realtime"));

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws IOException {
		Client client = client(session);
		if (clients.values().stream().filter(c -> c.userId == client.userId).count() >= MAX_PER_USER) {
			session.close(CloseStatus.POLICY_VIOLATION);
			return;
		}
		clients.put(session.getId(), client);
		worker.execute(() -> {
			try {
				send(client, market(client.symbol));
			} catch (BusinessException e) {
				// 없는 종목이다.
				close(client, CloseStatus.BAD_DATA);
			}
		});
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
		session.close(CloseStatus.NOT_ACCEPTABLE);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		clients.remove(session.getId());
	}

	/** 바뀐 종목과 사용자를 모아 잠시 뒤 한 번에 보낸다. 체결이 몰려도 화면마다 초당 5번을 넘지 않는다. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onSettled(Settled event) {
		if (clients.isEmpty()) {
			return;
		}
		dirtySymbols.add(event.symbol());
		dirtyUsers.addAll(event.userIds());
		if (scheduled.compareAndSet(false, true)) {
			worker.schedule(this::flush, FLUSH_MILLIS, TimeUnit.MILLISECONDS);
		}
	}

	/** 로그아웃하거나 세션이 끝나면 그 세션의 연결을 끊는다. */
	@EventListener
	void onSessionDestroyed(HttpSessionDestroyedEvent event) {
		clients.values().stream().filter(c -> event.getId().equals(c.httpSessionId))
			.forEach(c -> close(c, CloseStatus.POLICY_VIOLATION));
	}

	/** 다시 로그인해 세션 ID가 바뀌면 새 ID를 따라간다. */
	@EventListener
	void onSessionIdChanged(HttpSessionIdChangedEvent event) {
		clients.values().stream().filter(c -> event.getOldSessionId().equals(c.httpSessionId))
			.forEach(c -> c.httpSessionId = event.getNewSessionId());
	}

	/** 남은 연결을 끊고 멈춘다. */
	@PreDestroy
	void stop() {
		worker.shutdownNow();
		clients.values().forEach(c -> close(c, CloseStatus.GOING_AWAY));
	}

	// 모인 변경을 보낸다. 보내는 동안 들어온 변경은 다음에 보낸다.
	private void flush() {
		scheduled.set(false);
		Set<String> symbols = drain(dirtySymbols);
		Set<Long> users = drain(dirtyUsers);
		try {
			String account = jsonMapper.writeValueAsString(RealtimeMessage.account());
			for (String symbol : symbols) {
				Set<Client> watching = new HashSet<>(clients.values());
				watching.removeIf(c -> !c.symbol.equals(symbol));
				if (!watching.isEmpty()) {
					String market = market(symbol);
					watching.forEach(c -> send(c, market));
				}
			}
			clients.values().stream().filter(c -> users.contains(c.userId)).forEach(c -> send(c, account));
		} catch (RuntimeException e) {
			// 화면은 주기적인 조회로도 갱신되므로 다음 변경 때 다시 보낸다.
			log.warn("실시간 전송 실패", e);
		}
	}

	// 모아 둔 값을 꺼내며 비운다.
	private static <T> Set<T> drain(Set<T> source) {
		Set<T> drained = new HashSet<>(source);
		source.removeAll(drained);
		return drained;
	}

	// 종목의 호가와 최근 체결.
	private String market(String symbol) {
		return jsonMapper.writeValueAsString(
			RealtimeMessage.market(orderService.book(symbol), executionService.recentTrades(symbol)));
	}

	// 느린 화면 하나가 다른 화면을 막지 않도록, 밀리면 연결을 끊는다.
	private void send(Client client, String payload) {
		try {
			client.session.sendMessage(new TextMessage(payload));
		} catch (IOException | RuntimeException e) {
			close(client, CloseStatus.SESSION_NOT_RELIABLE);
		}
	}

	// 연결을 끊고 목록에서 뺀다.
	private void close(Client client, CloseStatus status) {
		clients.remove(client.session.getId());
		try {
			client.session.close(status);
		} catch (IOException | RuntimeException e) {
			log.debug("이미 끊긴 연결", e);
		}
	}

	// 주소의 마지막 부분이 종목이다.
	private static Client client(WebSocketSession session) {
		URI uri = session.getUri();
		String path = uri == null ? "" : uri.getPath();
		LoginUser user = (LoginUser) ((Authentication) session.getPrincipal()).getPrincipal();
		return new Client(new ConcurrentWebSocketSessionDecorator(session, SEND_TIMEOUT_MILLIS, SEND_BUFFER_BYTES),
			path.substring(path.lastIndexOf('/') + 1), user.userId(),
			(String) session.getAttributes().get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME));
	}

	/** 연결된 화면 하나. */
	private static final class Client {

		private final WebSocketSession session;
		private final String symbol;
		private final long userId;
		private volatile String httpSessionId;

		// 연결과 보는 종목, 사용자, HTTP 세션을 묶는다.
		private Client(WebSocketSession session, String symbol, long userId, String httpSessionId) {
			this.session = session;
			this.symbol = symbol;
			this.userId = userId;
			this.httpSessionId = httpSessionId;
		}
	}
}
