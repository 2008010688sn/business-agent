/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.config;

import com.sn68.agent.dataagent.service.realtime.RealtimeVoiceWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 实时语音 WebSocket 配置：注册语音会话处理器与握手拦截。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class RealtimeVoiceWebSocketConfig implements WebSocketConfigurer {

	private final RealtimeVoiceWebSocketHandler realtimeVoiceWebSocketHandler;

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(realtimeVoiceWebSocketHandler, "/ws/ai/realtime", "/ws/ai/realtime/voice")
			.setAllowedOriginPatterns("*");
	}

}
