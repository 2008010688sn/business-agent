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
package com.sn68.agent.dataagent.service.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * StepFun ASR SSE 转写客户端。
 */
@Component
@RequiredArgsConstructor
public class StepFunAsrTranscriptionClient {

	private static final String STEP_FUN_ASR_MODEL = "stepaudio-2.5-asr";

	private static final String STEP_FUN_ASR_SSE_PATH = "/v1/audio/asr/sse";

	private static final String STEP_FUN_ASR_SSE_PATH_WITHOUT_VERSION = "/audio/asr/sse";

	private final DynamicModelFactory dynamicModelFactory;

	private final ObjectMapper objectMapper;

	public boolean supports(ModelConfigDTO config) {
		if (config == null || !STEP_FUN_ASR_MODEL.equalsIgnoreCase(trim(config.getModelName()))) {
			return false;
		}
		return lower(config.getBaseUrl()).contains("stepfun")
				|| lower(config.getTranscriptionsPath()).contains("/audio/asr/sse");
	}

	public String transcribe(ModelConfigDTO config, MultipartFile file) {
		validateConfig(config);
		String audioFormat = resolveAudioFormat(file);
		RestClient.Builder builder = dynamicModelFactory.createRestClientBuilder(config)
			.baseUrl(normalizeBaseUrl(config.getBaseUrl()))
			.defaultHeader(HttpHeaders.AUTHORIZATION, bearer(config.getApiKey()));
		byte[] responseBody = builder.build()
			.post()
			.uri(resolveTranscriptionsPath(config))
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.body(createRequest(config, file, audioFormat))
			.retrieve()
			.body(byte[].class);
		return extractText(responseBody == null ? "" : new String(responseBody, StandardCharsets.UTF_8));
	}

	private void validateConfig(ModelConfigDTO config) {
		if (config == null || !StringUtils.hasText(config.getBaseUrl())) {
			throw CheckedException.badRequest("StepFun 语音转写模型 Base URL 不能为空。");
		}
		if (!StringUtils.hasText(config.getApiKey())) {
			throw CheckedException.badRequest("StepFun 语音转写模型 API Key 不能为空。");
		}
		if (!StringUtils.hasText(config.getModelName())) {
			throw CheckedException.badRequest("StepFun 语音转写模型名称不能为空。");
		}
	}

	private Map<String, Object> createRequest(ModelConfigDTO config, MultipartFile file, String audioFormat) {
		Map<String, Object> transcription = new LinkedHashMap<>();
		transcription.put("model", config.getModelName());
		transcription.put("language", "zh");
		transcription.put("enable_itn", true);
		transcription.put("enable_timestamp", false);

		Map<String, Object> input = new LinkedHashMap<>();
		input.put("transcription", transcription);
		input.put("format", Map.of("type", audioFormat));

		return Map.of("audio", Map.of("data", Base64.getEncoder().encodeToString(readAudioBytes(file)), "input", input));
	}

	private String extractText(String responseBody) {
		if (!StringUtils.hasText(responseBody)) {
			throw CheckedException.fail("StepFun 语音转写失败：SSE 响应为空。");
		}
		String normalized = responseBody.replace("\r\n", "\n").replace('\r', '\n');
		for (String event : normalized.split("\n\n")) {
			String data = extractEventData(event);
			if (!StringUtils.hasText(data) || "[DONE]".equals(data)) {
				continue;
			}
			JsonNode payload = readEventPayload(data);
			String type = payload.path("type").asText();
			if ("error".equals(type)) {
				throw CheckedException.fail("StepFun 语音转写失败：" + payload.path("message").asText("未知错误。"));
			}
			if ("transcript.text.done".equals(type)) {
				JsonNode text = payload.path("text");
				if (!text.isTextual()) {
					throw CheckedException.fail("StepFun 语音转写失败：最终转写事件缺少文本。");
				}
				return text.asText().trim();
			}
		}
		throw CheckedException.fail("StepFun 语音转写失败：未收到最终转写事件。");
	}

	private String extractEventData(String event) {
		StringBuilder builder = new StringBuilder();
		for (String line : event.split("\n")) {
			if (!line.startsWith("data:")) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append('\n');
			}
			builder.append(line.substring("data:".length()).stripLeading());
		}
		return builder.toString();
	}

	private JsonNode readEventPayload(String data) {
		try {
			return objectMapper.readTree(data);
		}
		catch (Exception ex) {
			throw CheckedException.fail("StepFun 语音转写失败：SSE 响应格式无效。");
		}
	}

	private String resolveAudioFormat(MultipartFile file) {
		String contentType = file == null ? null : file.getContentType();
		String normalized = trim(contentType).toLowerCase(Locale.ROOT);
		if (normalized.contains(";")) {
			normalized = normalized.substring(0, normalized.indexOf(';')).trim();
		}
		return switch (normalized) {
			case "audio/wav", "audio/x-wav" -> "wav";
			case "audio/mpeg", "audio/mp3" -> "mp3";
			case "audio/ogg" -> "ogg";
			default -> throw CheckedException.badRequest("StepFun 语音转写仅支持 WAV、MP3、OGG 音频。");
		};
	}

	private byte[] readAudioBytes(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (IOException ex) {
			throw CheckedException.fail("读取音频文件失败：" + ex.getMessage());
		}
	}

	private String resolveTranscriptionsPath(ModelConfigDTO config) {
		if (StringUtils.hasText(config.getTranscriptionsPath())) {
			return config.getTranscriptionsPath().trim();
		}
		return normalizeBaseUrl(config.getBaseUrl()).toLowerCase(Locale.ROOT).endsWith("/v1")
				? STEP_FUN_ASR_SSE_PATH_WITHOUT_VERSION : STEP_FUN_ASR_SSE_PATH;
	}

	private String normalizeBaseUrl(String baseUrl) {
		String normalized = baseUrl.trim();
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private String bearer(String apiKey) {
		String value = apiKey.trim();
		return value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length()) ? value : "Bearer " + value;
	}

	private String lower(String value) {
		return trim(value).toLowerCase(Locale.ROOT);
	}

	private String trim(String value) {
		return value == null ? "" : value.trim();
	}

}
