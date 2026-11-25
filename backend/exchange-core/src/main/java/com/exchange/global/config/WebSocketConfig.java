package com.exchange.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import com.exchange.domain.realtime.handler.RealtimeHandler;

import lombok.RequiredArgsConstructor;

/** 실시간 화면용 WebSocket. 허용 출처를 지정하지 않아 같은 출처에서만 연결할 수 있다. */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

	private final RealtimeHandler realtimeHandler;

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// 로그아웃할 때 끊을 연결을 찾기 위해 HTTP 세션 ID를 넘긴다.
		registry.addHandler(realtimeHandler, "/ws/quotes/*").addInterceptors(new HttpSessionHandshakeInterceptor());
	}

	/** 세션이 끝나거나 ID가 바뀐 것을 이벤트로 알린다. */
	@Bean
	HttpSessionEventPublisher httpSessionEventPublisher() {
		return new HttpSessionEventPublisher();
	}
}
