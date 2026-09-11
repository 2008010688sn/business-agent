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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通义 Qwen-ASR 转写客户端：调用 DashScope 接口完成音频转文本。
 */
@Component
@RequiredArgsConstructor
public class QwenAsrTranscriptionClient {

	static final long MAX_QWEN_ASR_AUDIO_SIZE = 10L * 1024 * 1024;

	private static final String QWEN_PROVIDER = "qwen";

	private static final String QWEN_ASR_MODEL_PREFIX = "qwen3-asr-flash";

	private static final String QWEN_ASR_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

	private static final String QWEN_ASR_CHAT_COMPLETIONS_PATH_WITHOUT_VERSION = "/chat/completions";

	private final DynamicModelFactory dynamicModelFactory;

	public boolean supports(ModelConfigDTO config) {
		if (config == null || !QWEN_PROVIDER.equalsIgnoreCase(config.getProvider())) {
			return false;
		}
		String modelName = config.getModelName();
		if (!StringUtils.hasText(modelName)) {
			return false;
		}
		String normalizedModelName = modelName.trim().toLowerCase(Locale.ROOT);
		return normalizedModelName.startsWith(QWEN_ASR_MODEL_PREFIX)
				&& !normalizedModelName.contains("realtime")
				&& !normalizedModelName.contains("filetrans");
	}

	public String transcribe(ModelConfigDTO config, MultipartFile file) {
		validateConfig(config);
		validateAudioSize(file);
		byte[] audioBytes = readAudioBytes(file);
		RestClient client = dynamicModelFactory.createRestClientBuilder(config)
			.baseUrl(normalizeBaseUrl(config.getBaseUrl()))
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
			.build();

		QwenAsrResponse response = client.post()
			.uri(resolveChatCompletionsPath(config.getBaseUrl()))
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON)
			.body(createRequest(config, file, audioBytes))
			.retrieve()
			.body(QwenAsrResponse.class);
		return extractText(response);
	}

	private void validateConfig(ModelConfigDTO config) {
		if (!StringUtils.hasText(config.getBaseUrl())) {
			throw CheckedException.badRequest("语音转写模型 Base URL 不能为空。");
		}
		if (!StringUtils.hasText(config.getApiKey())) {
			throw CheckedException.badRequest("阿里 Qwen-ASR 语音转写模型 API Key 不能为空。");
		}
		if (!StringUtils.hasText(config.getModelName())) {
			throw CheckedException.badRequest("阿里 Qwen-ASR 语音转写模型名称不能为空。");
		}
	}

	private void validateAudioSize(MultipartFile file) {
		if (file.getSize() > MAX_QWEN_ASR_AUDIO_SIZE) {
			throw CheckedException.badRequest("阿里 Qwen-ASR 短音频转写大小不能超过 10MB。");
		}
	}

	private byte[] readAudioBytes(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (IOException ex) {
			throw CheckedException.fail("读取音频文件失败：" + ex.getMessage());
		}
	}

	private QwenAsrRequest createRequest(ModelConfigDTO config, MultipartFile file, byte[] audioBytes) {
		String dataUrl = "data:" + normalizeContentType(file.getContentType()) + ";base64,"
				+ Base64.getEncoder().encodeToString(audioBytes);
		return new QwenAsrRequest(config.getModelName(),
				List.of(new QwenAsrMessage("user",
						List.of(new QwenAsrContent("input_audio", new QwenAsrInputAudio(dataUrl))))),
				false);
	}

	private String extractText(QwenAsrResponse response) {
		if (response == null || response.choices() == null || response.choices().isEmpty()) {
			throw CheckedException.fail("语音转写失败：阿里 Qwen-ASR 响应为空。");
		}
		QwenAsrChoice choice = response.choices().get(0);
		if (choice == null || choice.message() == null || choice.message().content() == null
				|| choice.message().content().isNull()) {
			return "";
		}
		JsonNode content = choice.message().content();
		return content.isTextual() ? content.asText().trim() : content.toString().trim();
	}

	private String normalizeBaseUrl(String baseUrl) {
		String normalized = baseUrl.trim();
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private String resolveChatCompletionsPath(String baseUrl) {
		String normalizedBaseUrl = normalizeBaseUrl(baseUrl).toLowerCase(Locale.ROOT);
		if (normalizedBaseUrl.endsWith("/v1")) {
			return QWEN_ASR_CHAT_COMPLETIONS_PATH_WITHOUT_VERSION;
		}
		return QWEN_ASR_CHAT_COMPLETIONS_PATH;
	}

	private String normalizeContentType(String contentType) {
		String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
		return "video/webm".equals(normalized) ? "audio/webm" : normalized;
	}

	private record QwenAsrRequest(String model, List<QwenAsrMessage> messages, Boolean stream) {
	}

	private record QwenAsrMessage(String role, List<QwenAsrContent> content) {
	}

	private record QwenAsrContent(String type, @JsonProperty("input_audio") QwenAsrInputAudio inputAudio) {
	}

	private record QwenAsrInputAudio(String data) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record QwenAsrResponse(List<QwenAsrChoice> choices) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record QwenAsrChoice(QwenAsrResponseMessage message) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record QwenAsrResponseMessage(JsonNode content) {
	}

}
