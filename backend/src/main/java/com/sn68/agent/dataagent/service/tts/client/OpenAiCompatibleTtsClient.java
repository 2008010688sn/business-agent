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
package com.sn68.agent.dataagent.service.tts.client;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechReq;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechResult;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import com.sn68.agent.dataagent.entity.TtsVoiceSample;
import com.sn68.agent.dataagent.enums.VoiceSourceType;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.tts.impl.TtsJsonSupport;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容协议的 TTS 客户端：调用 audio/speech 接口合成音频。
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleTtsClient implements TextToSpeechClient {

	private static final String DEFAULT_SPEECH_PATH = "/v1/audio/speech";

	private static final String DEFAULT_SPEECH_PATH_WITHOUT_VERSION = "/audio/speech";

	private final DynamicModelFactory dynamicModelFactory;

	private final TtsJsonSupport jsonSupport;

	@Override
	public boolean supports(ModelConfigDTO modelConfig, ModelTtsConfig ttsConfig, TtsVoiceProfile voiceProfile) {
		Map<String, Object> options = jsonSupport.readObject(ttsConfig.getOptions());
		String apiFamily = text(options.get("apiFamily"));
		String vendor = text(options.get("vendor"));
		if ("aliyun".equalsIgnoreCase(vendor) || isAliyunFamily(apiFamily)) {
			return false;
		}
		if ("openai-compatible".equalsIgnoreCase(apiFamily) || "openai".equalsIgnoreCase(apiFamily)) {
			return true;
		}
		String provider = modelConfig == null ? null : modelConfig.getProvider();
		return "openai".equalsIgnoreCase(provider) || "custom".equalsIgnoreCase(provider);
	}

	@Override
	public AudioSpeechResult synthesize(ModelConfigDTO modelConfig, ModelTtsConfig ttsConfig,
			TtsVoiceProfile voiceProfile, TtsVoiceSample voiceSample, AudioSpeechReq request) {
		validate(modelConfig, request);
		String format = firstText(request.getFormat(), voiceProfile.getSpeechFormat(), ttsConfig.getDefaultFormat(),
				"mp3");
		RestClient.Builder builder = dynamicModelFactory.createRestClientBuilder(modelConfig)
			.baseUrl(normalizeBaseUrl(modelConfig.getBaseUrl()));
		if (StringUtils.hasText(modelConfig.getApiKey())) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + modelConfig.getApiKey());
		}
		ResponseEntity<byte[]> response = builder.build()
			.post()
			.uri(resolveSpeechPath(modelConfig, ttsConfig))
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.valueOf(contentType(format)),
					MediaType.APPLICATION_JSON)
			.body(createRequestBody(modelConfig, ttsConfig, voiceProfile, voiceSample, request, format))
			.retrieve()
			.toEntity(byte[].class);
		byte[] audio = response.getBody();
		if (audio == null || audio.length == 0) {
			throw CheckedException.fail("语音合成失败：TTS 服务响应为空。");
		}
		String contentType = response.getHeaders().getContentType() == null ? contentType(format)
				: response.getHeaders().getContentType().toString();
		return AudioSpeechResult.builder().audio(audio).contentType(contentType).format(format).build();
	}

	private Map<String, Object> createRequestBody(ModelConfigDTO modelConfig, ModelTtsConfig ttsConfig,
			TtsVoiceProfile voiceProfile, TtsVoiceSample voiceSample, AudioSpeechReq request, String format) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", modelConfig.getModelName());
		body.put("input", request.getText());
		body.put("voice", firstText(voiceProfile.getVoiceName(), "default"));
		body.put("response_format", format);
		body.put("sample_rate", firstInt(request.getSampleRate(), voiceProfile.getSampleRate(),
				ttsConfig.getDefaultSampleRate()));
		body.put("speed", firstDecimal(request.getSpeed(), voiceProfile.getSpeechSpeed(), BigDecimal.ONE));
		body.put("pitch", voiceProfile.getPitch());
		body.put("volume_gain", voiceProfile.getVolumeGain());
		body.put("language", voiceProfile.getLanguageCode());
		body.put("voice_source", firstText(voiceProfile.getVoiceSource(), VoiceSourceType.SYSTEM.getCode()));
		body.putAll(jsonSupport.readObject(ttsConfig.getOptions()));
		body.putAll(jsonSupport.readObject(voiceProfile.getOptions()));
		if (request.getOptions() != null) {
			body.putAll(request.getOptions());
		}
		if (VoiceSourceType.LOCAL_REFERENCE.getCode().equalsIgnoreCase(voiceProfile.getVoiceSource())) {
			if (voiceSample == null) {
				throw CheckedException.badRequest("本地参考音色缺少样本。");
			}
			body.put("reference_audio", voiceSample.getFilePath());
			body.put("reference_text", voiceSample.getTranscript());
			body.put("sample_file_id", voiceSample.getFileId());
		}
		return body;
	}

	private void validate(ModelConfigDTO modelConfig, AudioSpeechReq request) {
		if (!StringUtils.hasText(modelConfig.getBaseUrl())) {
			throw CheckedException.badRequest("TTS 模型 Base URL 不能为空。");
		}
		if (!StringUtils.hasText(modelConfig.getModelName())) {
			throw CheckedException.badRequest("TTS 模型名称不能为空。");
		}
		if (!"custom".equalsIgnoreCase(modelConfig.getProvider()) && !StringUtils.hasText(modelConfig.getApiKey())) {
			throw CheckedException.badRequest("TTS 模型 API Key 不能为空。");
		}
		if (request == null || !StringUtils.hasText(request.getText())) {
			throw CheckedException.badRequest("播报文本不能为空。");
		}
	}

	private String resolveSpeechPath(ModelConfigDTO modelConfig, ModelTtsConfig ttsConfig) {
		if (StringUtils.hasText(ttsConfig.getSpeechPath())) {
			return ttsConfig.getSpeechPath().trim();
		}
		String normalizedBaseUrl = normalizeBaseUrl(modelConfig.getBaseUrl()).toLowerCase(Locale.ROOT);
		if (normalizedBaseUrl.endsWith("/v1")) {
			return DEFAULT_SPEECH_PATH_WITHOUT_VERSION;
		}
		return DEFAULT_SPEECH_PATH;
	}

	private String normalizeBaseUrl(String baseUrl) {
		String normalized = baseUrl.trim();
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private String contentType(String format) {
		String normalized = StringUtils.hasText(format) ? format.trim().toLowerCase(Locale.ROOT) : "mp3";
		return switch (normalized) {
			case "wav" -> "audio/wav";
			case "ogg" -> "audio/ogg";
			case "opus" -> "audio/opus";
			case "pcm" -> "audio/pcm";
			default -> "audio/mpeg";
		};
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

	private Integer firstInt(Integer... values) {
		if (values == null) {
			return null;
		}
		for (Integer value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private BigDecimal firstDecimal(BigDecimal... values) {
		if (values == null) {
			return null;
		}
		for (BigDecimal value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private boolean isAliyunFamily(String apiFamily) {
		if (!StringUtils.hasText(apiFamily)) {
			return false;
		}
		String normalized = apiFamily.trim().toLowerCase(Locale.ROOT);
		return normalized.equals("sambert") || normalized.equals("cosyvoice") || normalized.equals("cosy-voice")
				|| normalized.equals("qwen-tts") || normalized.equals("qwen_tts")
				|| normalized.equals("qwen-tts-realtime") || normalized.equals("qwen_tts_realtime");
	}

	private String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

}
