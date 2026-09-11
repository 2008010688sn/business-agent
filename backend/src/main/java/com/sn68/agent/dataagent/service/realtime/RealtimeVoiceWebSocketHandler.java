/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.RealtimeVoiceSession;
import com.sn68.agent.dataagent.enums.RealtimeVoiceErrorDict;
import com.sn68.agent.dataagent.service.realtime.client.RealtimeVoiceEventSink;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 实时语音WebSocket组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeVoiceWebSocketHandler extends AbstractWebSocketHandler {

	private static final String ATTR_REALTIME_SESSION = "realtimeVoiceSession";

	private final ObjectMapper objectMapper;

	private final RealtimeVoiceSessionService sessionService;

	private final RealtimeVoiceOrchestrator orchestrator;

	/**
	 * 处理实时语音WebSocket。
	 */
	@Override
	public void afterConnectionEstablished(WebSocketSession webSocketSession) throws Exception {
		Map<String, String> query = queryParams(webSocketSession);
		RealtimeVoiceSession realtimeSession = sessionService.validateWebSocketToken(query.get("sessionId"),
				query.get("token"));
		webSocketSession.getAttributes().put(ATTR_REALTIME_SESSION, realtimeSession);
		sessionService.markActive(realtimeSession.getSessionId());
		orchestrator.openRealtime(realtimeSession, eventSink(webSocketSession));
		sendEvent(webSocketSession, "session.created", Map.of("sessionId", realtimeSession.getSessionId(),
				"runtimeMode", realtimeSession.getRuntimeMode(), "transport", realtimeSession.getTransport()));
	}

	@Override
	protected void handleTextMessage(WebSocketSession webSocketSession, TextMessage message) throws Exception {
		RealtimeVoiceSession realtimeSession = realtimeSession(webSocketSession);
		Map<String, Object> payload = parsePayload(message.getPayload());
		String event = firstText(asText(payload.get("event")), asText(payload.get("type")));
		try {
			switch (event) {
				case "ping" -> sendEvent(webSocketSession, "pong", Map.of("ts", System.currentTimeMillis()));
				case "session.start" -> sendEvent(webSocketSession, "session.started",
						Map.of("sessionId", realtimeSession.getSessionId()));
				case "input_audio.commit" -> commit(webSocketSession, realtimeSession, payload);
				case "response.cancel" -> cancel(webSocketSession, realtimeSession);
				case "session.close" -> closeSession(webSocketSession, realtimeSession);
				default -> sendEvent(webSocketSession, "session.status",
						Map.of("status", "UNKNOWN_EVENT", "event", event == null ? "" : event));
			}
		}
		catch (CheckedException ex) {
			sendError(webSocketSession, resolveError(ex, RealtimeVoiceErrorDict.AGENT_FAILED), ex.getMessage());
		}
		catch (Exception ex) {
			log.error("Realtime voice websocket event failed. sessionId={}, event={}", realtimeSession.getSessionId(),
					event, ex);
			sendError(webSocketSession, RealtimeVoiceErrorDict.AGENT_FAILED, ex.getMessage());
		}
	}

	@Override
	protected void handleBinaryMessage(WebSocketSession webSocketSession, BinaryMessage message) throws Exception {
		RealtimeVoiceSession realtimeSession = realtimeSession(webSocketSession);
		ByteBuffer payload = message.getPayload();
		byte[] bytes = new byte[payload.remaining()];
		payload.get(bytes);
		orchestrator.appendAudio(realtimeSession, bytes, null);
	}

	/**
	 * 处理实时语音WebSocket。
	 */
	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
		log.warn("Realtime voice websocket transport error. sessionId={}", session.getId(), exception);
		if (session.isOpen()) {
			session.close(CloseStatus.SERVER_ERROR);
		}
	}

	/**
	 * 处理实时语音WebSocket。
	 */
	@Override
	public void afterConnectionClosed(WebSocketSession webSocketSession, CloseStatus status) throws Exception {
		Object value = webSocketSession.getAttributes().get(ATTR_REALTIME_SESSION);
		if (value instanceof RealtimeVoiceSession realtimeSession) {
			orchestrator.close(realtimeSession);
			try {
				sessionService.closeBySessionId(realtimeSession.getSessionId());
			}
			catch (Exception ex) {
				log.debug("Realtime voice session already closed. sessionId={}", realtimeSession.getSessionId(), ex);
			}
		}
	}

	private void commit(WebSocketSession webSocketSession, RealtimeVoiceSession realtimeSession, Map<String, Object> payload)
			throws IOException {
		String turnId = firstText(asText(payload.get("turnId")), null);
		String contentType = firstText(asText(payload.get("contentType")), firstText(asText(payload.get("mimeType")),
				"audio/webm"));
		sendEvent(webSocketSession, "input_audio.processing", Map.of("turnId", turnId == null ? "" : turnId));
		if (orchestrator.isRealtime(realtimeSession)) {
			orchestrator.commitRealtime(realtimeSession);
			return;
		}
		RealtimeVoiceTurnResult result = orchestrator.commit(realtimeSession, turnId, contentType);
		sendEvent(webSocketSession, "asr.final",
				Map.of("turnId", result.turnId(), "sequence", result.sequence(), "text", result.transcript()));
		sendEvent(webSocketSession, "agent.text.done", Map.of("turnId", result.turnId(), "sequence", result.sequence(),
				"text", result.answerText() == null ? "" : result.answerText()));
		if (result.audio() != null && result.audio().length > 0) {
			sendBinary(webSocketSession, result.audio());
		}
		sendEvent(webSocketSession, "audio.output.done",
				Map.of("turnId", result.turnId(), "sequence", result.sequence(), "contentType",
						result.audioContentType() == null ? "application/octet-stream" : result.audioContentType(),
						"format", result.audioFormat() == null ? "" : result.audioFormat()));
	}

	private void cancel(WebSocketSession webSocketSession, RealtimeVoiceSession realtimeSession) throws IOException {
		boolean stopped = orchestrator.cancel(realtimeSession);
		sendEvent(webSocketSession, "response.cancelled",
				Map.of("sessionId", realtimeSession.getSessionId(), "stopped", stopped));
	}

	private void closeSession(WebSocketSession webSocketSession, RealtimeVoiceSession realtimeSession) throws IOException {
		orchestrator.close(realtimeSession);
		sessionService.closeBySessionId(realtimeSession.getSessionId());
		webSocketSession.getAttributes().remove(ATTR_REALTIME_SESSION);
		sendEvent(webSocketSession, "session.closed", Map.of("sessionId", realtimeSession.getSessionId()));
		try {
			webSocketSession.close(CloseStatus.NORMAL);
		}
		catch (IOException ex) {
			log.debug("Realtime voice websocket already closed. sessionId={}", realtimeSession.getSessionId(), ex);
		}
	}

	private RealtimeVoiceSession realtimeSession(WebSocketSession webSocketSession) {
		Object value = webSocketSession.getAttributes().get(ATTR_REALTIME_SESSION);
		if (value instanceof RealtimeVoiceSession realtimeSession) {
			return realtimeSession;
		}
		throw CheckedException.badRequest(RealtimeVoiceErrorDict.SESSION_NOT_FOUND.getValue(),
				RealtimeVoiceErrorDict.SESSION_NOT_FOUND.getLabel());
	}

	private Map<String, Object> parsePayload(String payload) throws IOException {
		if (!StringUtils.hasText(payload)) {
			return Map.of();
		}
		return objectMapper.readValue(payload, new TypeReference<>() {
		});
	}

	private void sendEvent(WebSocketSession session, String event, Map<String, Object> data) throws IOException {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("event", event);
		response.put("data", data == null ? Map.of() : data);
		synchronized (session) {
			if (session.isOpen()) {
				session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
			}
		}
	}

	private void sendError(WebSocketSession session, RealtimeVoiceErrorDict dict, String detail) throws IOException {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("code", dict.getValue());
		data.put("message", dict.getLabel());
		if (StringUtils.hasText(detail)) {
			data.put("detail", detail);
		}
		sendEvent(session, "error", data);
	}

	private void sendBinary(WebSocketSession session, byte[] bytes) throws IOException {
		synchronized (session) {
			if (session.isOpen()) {
				session.sendMessage(new BinaryMessage(bytes));
			}
		}
	}

	private RealtimeVoiceEventSink eventSink(WebSocketSession session) {
		return new RealtimeVoiceEventSink() {
			@Override
			public void sendEvent(String event, Map<String, Object> data) throws IOException {
				RealtimeVoiceWebSocketHandler.this.sendEvent(session, event, data);
			}

			@Override
			public void sendAudio(byte[] bytes) throws IOException {
				RealtimeVoiceWebSocketHandler.this.sendBinary(session, bytes);
			}
		};
	}

	private Map<String, String> queryParams(WebSocketSession session) {
		Map<String, String> result = new LinkedHashMap<>();
		if (session.getUri() == null) {
			return result;
		}
		UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams()
			.forEach((key, values) -> result.put(key, values == null || values.isEmpty() ? null : values.get(0)));
		return result;
	}

	private RealtimeVoiceErrorDict resolveError(Throwable ex, RealtimeVoiceErrorDict fallback) {
		String message = ex == null ? null : ex.getMessage();
		for (RealtimeVoiceErrorDict dict : RealtimeVoiceErrorDict.values()) {
			if (StringUtils.hasText(message) && message.contains(dict.getLabel())) {
				return dict;
			}
		}
		return fallback;
	}

	private String asText(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private String firstText(String value, String fallback) {
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

}
