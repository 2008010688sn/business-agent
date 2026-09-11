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
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * OpenAI 兼容协议的语音转写客户端：调用 audio/transcriptions 接口完成转写。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleAudioTranscriptionClient {

	private static final String DEFAULT_TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions";

	private static final String DEFAULT_TRANSCRIPTIONS_PATH_WITHOUT_VERSION = "/audio/transcriptions";

	private final DynamicModelFactory dynamicModelFactory;

	private final ObjectMapper objectMapper = new ObjectMapper();

	public String transcribe(ModelConfigDTO config, MultipartFile file) {
		validateConfig(config);
		RestClient.Builder builder = dynamicModelFactory.createRestClientBuilder(config)
			.baseUrl(normalizeBaseUrl(config.getBaseUrl()));
		if (StringUtils.hasText(config.getApiKey())) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey());
		}
		String responseBody = builder.build()
			.post()
			.uri(resolveTranscriptionsPath(config))
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.body(createMultipartBody(config, file))
			.retrieve()
			.body(String.class);
		return extractText(responseBody);
	}

	private void validateConfig(ModelConfigDTO config) {
		if (!StringUtils.hasText(config.getBaseUrl())) {
			throw CheckedException.badRequest("语音转写模型 Base URL 不能为空。");
		}
		if (!StringUtils.hasText(config.getModelName())) {
			throw CheckedException.badRequest("语音转写模型名称不能为空。");
		}
		if (!"custom".equalsIgnoreCase(config.getProvider()) && !StringUtils.hasText(config.getApiKey())) {
			throw CheckedException.badRequest("语音转写模型 API Key 不能为空。");
		}
	}

	private MultiValueMap<String, Object> createMultipartBody(ModelConfigDTO config, MultipartFile file) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", createFilePart(file));
		body.add("model", config.getModelName());
		body.add("response_format", "json");
		body.add("stream", false);
		if (config.getTemperature() != null) {
			body.add("temperature", config.getTemperature().floatValue());
		}
		return body;
	}

	private HttpEntity<ByteArrayResource> createFilePart(MultipartFile file) {
		ByteArrayResource resource = new ByteArrayResource(readAudioBytes(file)) {
			@Override
			public String getFilename() {
				return StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "audio.webm";
			}
		};
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(normalizeContentType(file.getContentType())));
		return new HttpEntity<>(resource, headers);
	}

	private byte[] readAudioBytes(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (IOException ex) {
			throw CheckedException.fail("读取音频文件失败：" + ex.getMessage());
		}
	}

	private String extractText(String responseBody) {
		if (!StringUtils.hasText(responseBody)) {
			return "";
		}
		String trimmed = responseBody.trim();
		try {
			JsonNode root = objectMapper.readTree(trimmed);
			JsonNode text = root.path("text");
			if (text.isTextual()) {
				return text.asText().trim();
			}
			JsonNode content = root.path("choices").path(0).path("message").path("content");
			if (content.isTextual()) {
				return content.asText().trim();
			}
		}
		catch (Exception ignored) {
			return trimmed;
		}
		return trimmed;
	}

	private String resolveTranscriptionsPath(ModelConfigDTO config) {
		if (StringUtils.hasText(config.getTranscriptionsPath())) {
			return config.getTranscriptionsPath().trim();
		}
		String normalizedBaseUrl = normalizeBaseUrl(config.getBaseUrl()).toLowerCase(Locale.ROOT);
		if (normalizedBaseUrl.endsWith("/v1")) {
			return DEFAULT_TRANSCRIPTIONS_PATH_WITHOUT_VERSION;
		}
		return DEFAULT_TRANSCRIPTIONS_PATH;
	}

	private String normalizeBaseUrl(String baseUrl) {
		String normalized = baseUrl.trim();
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private String normalizeContentType(String contentType) {
		String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
		return "video/webm".equals(normalized) ? "audio/webm" : normalized;
	}

}
