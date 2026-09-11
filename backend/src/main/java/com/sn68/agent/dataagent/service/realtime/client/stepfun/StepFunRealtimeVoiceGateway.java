/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime.client.stepfun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.ModelRealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.enums.RealtimeVoiceErrorDict;
import com.sn68.agent.dataagent.service.realtime.client.RealtimeVoiceEventSink;
import com.sn68.agent.dataagent.service.realtime.client.RealtimeVoiceProviderGateway;
import com.sn68.agent.dataagent.service.realtime.client.RealtimeVoiceProviderRequest;
import com.sn68.agent.dataagent.service.realtime.client.RealtimeVoiceProviderSession;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * StepFun实时语音Gateway组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepFunRealtimeVoiceGateway implements RealtimeVoiceProviderGateway {

	private static final String DEFAULT_REALTIME_PATH = "/v1/realtime";

	private static final String DEFAULT_INPUT_AUDIO_FORMAT = "pcm16";

	private static final String DEFAULT_OUTPUT_AUDIO_FORMAT = "pcm16";

	private final ObjectMapper objectMapper;

	/**
	 * 处理StepFun实时语音Gateway。
	 */
	@Override
	public boolean supports(RealtimeVoiceProviderRequest request) {
		ModelConfigDTO modelConfig = request == null ? null : request.modelConfig();
		if (modelConfig == null) {
			return false;
		}
		String provider = lower(modelConfig.getProvider());
		String modelName = lower(modelConfig.getModelName());
		String baseUrl = lower(modelConfig.getBaseUrl());
		return provider.contains("step") || provider.contains("阶跃") || modelName.startsWith("stepaudio-")
				|| baseUrl.contains("stepfun");
	}

	/**
	 * 执行StepFun实时语音Gateway。
	 */
	@Override
	public RealtimeVoiceProviderSession open(RealtimeVoiceProviderRequest request) {
		validate(request);
		try {
			HttpClient client = buildHttpClient(request.modelConfig(), request.conversationConfig());
			StepFunSession providerSession = new StepFunSession(request, objectMapper);
			WebSocket webSocket = client.newWebSocketBuilder()
				.header("Authorization", bearer(request.modelConfig().getApiKey()))
				.buildAsync(buildUri(request), providerSession)
				.get(connectTimeoutMs(request.conversationConfig()), TimeUnit.MILLISECONDS);
			providerSession.bind(webSocket);
			providerSession.updateSession();
			return providerSession;
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("StepFun realtime voice websocket connect failed. modelConfigId={}",
					request.modelConfig().getId(), ex);
			throw CheckedException.badRequest(RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY.getValue(),
					RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY.getLabel());
		}
	}

	private void validate(RealtimeVoiceProviderRequest request) {
		if (request == null || request.modelConfig() == null || request.realtimeVoiceConfig() == null) {
			throw CheckedException.badRequest(RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY.getValue(),
					RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY.getLabel());
		}
		if (!StringUtils.hasText(request.modelConfig().getApiKey())) {
			throw CheckedException.badRequest(RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY.getValue(),
					"REALTIME_VOICE provider key 未配置");
		}
		if (!StringUtils.hasText(request.modelConfig().getModelName())) {
			throw CheckedException.badRequest(RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY.getValue(),
					"REALTIME_VOICE modelName 未配置");
		}
	}

	private HttpClient buildHttpClient(ModelConfigDTO modelConfig, RealtimeVoiceConfigDTO config) {
		HttpClient.Builder builder = HttpClient.newBuilder()
			.connectTimeout(Duration.ofMillis(connectTimeoutMs(config)));
		if (Boolean.TRUE.equals(modelConfig.getProxyEnabled()) && StringUtils.hasText(modelConfig.getProxyHost())
				&& modelConfig.getProxyPort() != null) {
			builder.proxy(ProxySelector.of(new InetSocketAddress(modelConfig.getProxyHost(), modelConfig.getProxyPort())));
			if (StringUtils.hasText(modelConfig.getProxyUsername()) && StringUtils.hasText(modelConfig.getProxyPassword())) {
				builder.authenticator(new Authenticator() {
					@Override
					protected PasswordAuthentication getPasswordAuthentication() {
						return new PasswordAuthentication(modelConfig.getProxyUsername(),
								modelConfig.getProxyPassword().toCharArray());
					}
				});
			}
		}
		return builder.build();
	}

	private URI buildUri(RealtimeVoiceProviderRequest request) {
		String websocketUrl = request.realtimeVoiceConfig().getWebsocketUrl();
		String raw = StringUtils.hasText(websocketUrl) ? websocketUrl : defaultWebsocketUrl(request.modelConfig());
		UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(toWebSocketScheme(raw));
		if (!hasQueryParam(raw, "model")) {
			builder.queryParam("model", request.modelConfig().getModelName());
		}
		return builder.build(true).toUri();
	}

	private String defaultWebsocketUrl(ModelConfigDTO modelConfig) {
		String baseUrl = firstText(modelConfig.getBaseUrl(), "https://api.stepfun.com");
		String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		if (normalized.endsWith("/realtime")) {
			return normalized;
		}
		if (normalized.endsWith("/v1")) {
			return normalized + "/realtime";
		}
		return normalized + DEFAULT_REALTIME_PATH;
	}

	private String toWebSocketScheme(String url) {
		if (url.startsWith("wss://") || url.startsWith("ws://")) {
			return url;
		}
		if (url.startsWith("https://")) {
			return "wss://" + url.substring("https://".length());
		}
		if (url.startsWith("http://")) {
			return "ws://" + url.substring("http://".length());
		}
		return url;
	}

	private boolean hasQueryParam(String url, String name) {
		return UriComponentsBuilder.fromUriString(url).build().getQueryParams().containsKey(name);
	}

	private String bearer(String apiKey) {
		String value = apiKey == null ? "" : apiKey.trim();
		return value.toLowerCase().startsWith("bearer ") ? value : "Bearer " + value;
	}

	private int connectTimeoutMs(RealtimeVoiceConfigDTO config) {
		Integer value = config == null ? null : config.getConnectTimeoutMs();
		return value == null || value <= 0 ? 10000 : value;
	}

	private String lower(String value) {
		return value == null ? "" : value.trim().toLowerCase();
	}

	private String firstText(String value, String fallback) {
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

	private static final class StepFunSession implements RealtimeVoiceProviderSession, WebSocket.Listener {

		private final RealtimeVoiceProviderRequest request;

		private final ObjectMapper objectMapper;

		private final StringBuilder partialText = new StringBuilder();

		private final ConcurrentLinkedQueue<String> pendingMessages = new ConcurrentLinkedQueue<>();

		private final StringBuilder fragmentBuffer = new StringBuilder();

		private volatile WebSocket webSocket;

		private StepFunSession(RealtimeVoiceProviderRequest request, ObjectMapper objectMapper) {
			this.request = request;
			this.objectMapper = objectMapper;
		}

		private void bind(WebSocket webSocket) {
			this.webSocket = webSocket;
			String message;
			while ((message = pendingMessages.poll()) != null) {
				send(message);
			}
		}

		private void updateSession() {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("type", "session.update");
			payload.put("session", sessionConfig());
			sendJson(payload);
		}

		@Override
		public void appendAudio(byte[] bytes) {
			if (bytes == null || bytes.length == 0) {
				return;
			}
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("type", "input_audio_buffer.append");
			payload.put("audio", Base64.getEncoder().encodeToString(bytes));
			sendJson(payload);
		}

		@Override
		public void commit() {
			sendJson(Map.of("type", "input_audio_buffer.commit"));
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("type", "response.create");
			response.put("response", responseConfig());
			sendJson(response);
		}

		@Override
		public void cancel() {
			sendJson(Map.of("type", "response.cancel"));
			sendJson(Map.of("type", "input_audio_buffer.clear"));
		}

		@Override
		public void close() {
			WebSocket socket = webSocket;
			if (socket != null) {
				try {
					socket.sendClose(WebSocket.NORMAL_CLOSURE, "client closed");
				}
				catch (Exception ex) {
					log.debug("StepFun realtime websocket already closed.", ex);
				}
			}
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			fragmentBuffer.append(data);
			if (last) {
				String payload = fragmentBuffer.toString();
				fragmentBuffer.setLength(0);
				handlePayload(payload);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			log.warn("StepFun realtime websocket error. sessionId={}", request.session().getSessionId(), error);
			sendError("实时语音供应商连接异常");
		}

		private Map<String, Object> sessionConfig() {
			ModelRealtimeVoiceConfigDTO voiceConfig = request.realtimeVoiceConfig();
			Map<String, Object> options = options();
			Map<String, Object> session = new LinkedHashMap<>();
			session.put("modalities", List.of("text", "audio"));
			session.put("instructions", firstOptionText(options, "instructions", "你是箱箱云业务助手，请用简洁自然的中文回答。"));
			session.put("voice", firstText(voiceConfig.getVoiceName(), firstOptionText(options, "voice", "linjiajiejie")));
			session.put("input_audio_format", audioFormat(voiceConfig.getInputAudioFormat(), DEFAULT_INPUT_AUDIO_FORMAT));
			session.put("output_audio_format", audioFormat(voiceConfig.getOutputAudioFormat(), DEFAULT_OUTPUT_AUDIO_FORMAT));
			if (Boolean.TRUE.equals(options.get("serverVadEnabled"))) {
				session.put("turn_detection", Map.of("type", firstText(voiceConfig.getTurnDetectionType(), "server_vad")));
			}
			Optional.ofNullable(options.get("temperature")).ifPresent(value -> session.put("temperature", value));
			Optional.ofNullable(options.get("maxResponseOutputTokens"))
				.ifPresent(value -> session.put("max_response_output_tokens", value));
			return session;
		}

		private Map<String, Object> responseConfig() {
			Map<String, Object> options = options();
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("modalities", List.of("text", "audio"));
			String instructions = textOption(options, "responseInstructions");
			if (StringUtils.hasText(instructions)) {
				response.put("instructions", instructions);
			}
			Optional.ofNullable(options.get("maxResponseOutputTokens"))
				.ifPresent(value -> response.put("max_output_tokens", value));
			return response;
		}

		private void handlePayload(String payload) {
			try {
				JsonNode node = objectMapper.readTree(payload);
				String type = node.path("type").asText();
				switch (type) {
					case "session.created", "session.updated" -> sendEvent("provider.session.updated",
							Map.of("provider", "stepfun", "type", type));
					case "conversation.item.input_audio_transcription.completed" ->
						sendAsrFinal(node.path("transcript").asText(""));
					case "response.text.delta", "response.audio_transcript.delta" ->
						appendText(node.path("delta").asText(""));
					case "response.text.done", "response.audio_transcript.done" ->
						sendTextDone(firstText(node.path("text").asText(null), node.path("transcript").asText(null)));
					case "response.audio.delta" -> sendAudio(node.path("delta").asText(""));
					case "response.done" -> {
						sendTextDone(null);
						sendEvent("audio.output.done", Map.of("contentType", "audio/pcm", "format", "PCM16"));
					}
					case "error" -> sendError(node.path("error").path("message").asText("实时语音供应商处理失败"));
					default -> {
						if (type != null && type.startsWith("input_audio_buffer.speech")) {
							sendEvent("input_audio.status", Map.of("type", type));
						}
					}
				}
			}
			catch (Exception ex) {
				log.debug("Ignore invalid StepFun realtime payload. payload={}", payload, ex);
			}
		}

		private void sendAsrFinal(String text) throws IOException {
			if (!StringUtils.hasText(text)) {
				return;
			}
			request.eventSink().sendEvent("asr.final", Map.of("text", text));
		}

		private void appendText(String delta) throws IOException {
			if (!StringUtils.hasText(delta)) {
				return;
			}
			partialText.append(delta);
			request.eventSink().sendEvent("agent.text.delta", Map.of("text", partialText.toString(), "delta", delta));
		}

		private void sendTextDone(String text) throws IOException {
			String resolved = firstText(text, partialText.toString());
			if (StringUtils.hasText(resolved)) {
				request.eventSink().sendEvent("agent.text.done", Map.of("text", resolved));
			}
			partialText.setLength(0);
		}

		private void sendAudio(String base64) throws IOException {
			if (!StringUtils.hasText(base64)) {
				return;
			}
			request.eventSink().sendAudio(Base64.getDecoder().decode(base64));
		}

		private void sendError(String message) {
			try {
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("code", RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY.getValue());
				data.put("message", message);
				request.eventSink().sendEvent("error", data);
			}
			catch (IOException ex) {
				log.debug("Realtime provider error event send failed.", ex);
			}
		}

		private void sendEvent(String event, Map<String, Object> data) throws IOException {
			request.eventSink().sendEvent(event, data);
		}

		private void sendJson(Map<String, Object> payload) {
			try {
				send(objectMapper.writeValueAsString(payload));
			}
			catch (Exception ex) {
				throw CheckedException.fail("实时语音供应商请求序列化失败");
			}
		}

		private void send(String payload) {
			WebSocket socket = webSocket;
			if (socket == null) {
				pendingMessages.add(payload);
				return;
			}
			socket.sendText(payload, true);
		}

		private Map<String, Object> options() {
			Map<String, Object> options = request.realtimeVoiceConfig().getOptions();
			if (options == null) {
				return Map.of();
			}
			return options;
		}

		private String audioFormat(String value, String fallback) {
			return firstText(value, fallback).toLowerCase().replace("_", "");
		}

		private String firstOptionText(Map<String, Object> options, String key, String fallback) {
			return firstText(textOption(options, key), fallback);
		}

		private String textOption(Map<String, Object> options, String key) {
			Object value = options.get(key);
			return value == null ? null : String.valueOf(value);
		}

		private String firstText(String value, String fallback) {
			return StringUtils.hasText(value) ? value.trim() : fallback;
		}

	}

}
