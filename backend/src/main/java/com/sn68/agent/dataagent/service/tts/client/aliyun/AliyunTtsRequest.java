/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tts.client.aliyun;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechReq;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 阿里 TTS 合成请求参数：聚合文本、音色、格式与超时等选项。
 */
record AliyunTtsRequest(ModelConfigDTO modelConfig, ModelTtsConfig ttsConfig, TtsVoiceProfile voiceProfile,
		AudioSpeechReq request, Map<String, Object> ttsOptions, Map<String, Object> voiceOptions,
		Map<String, Object> requestOptions, AliyunTtsApiFamily apiFamily, String text, String modelName,
		String voiceName, String format, Integer sampleRate, BigDecimal speechSpeed, BigDecimal pitch,
		BigDecimal volumeGain, int connectTimeoutMs, int readTimeoutMs, int firstAudioTimeoutMs, String workspace,
		String endpoint) {

	private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;

	private static final int DEFAULT_READ_TIMEOUT_MS = 60000;

	private static final int DEFAULT_FIRST_AUDIO_TIMEOUT_MS = 15000;

	static AliyunTtsRequest create(ModelConfigDTO modelConfig, ModelTtsConfig ttsConfig, TtsVoiceProfile voiceProfile,
			AudioSpeechReq request, Map<String, Object> ttsOptions, Map<String, Object> voiceOptions,
			AliyunTtsApiFamily apiFamily) {
		Map<String, Object> safeTtsOptions = copy(ttsOptions);
		Map<String, Object> safeVoiceOptions = copy(voiceOptions);
		Map<String, Object> safeRequestOptions = copy(request == null ? null : request.getOptions());
		String format = firstText(text(safeRequestOptions.get("format")), request == null ? null : request.getFormat(),
				voiceProfile == null ? null : voiceProfile.getSpeechFormat(),
				text(safeVoiceOptions.get("audioFormat")), text(safeTtsOptions.get("audioFormat")),
				ttsConfig == null ? null : ttsConfig.getDefaultFormat(), "mp3");
		Integer sampleRate = firstInt(integer(safeRequestOptions.get("sampleRate")),
				request == null ? null : request.getSampleRate(), voiceProfile == null ? null : voiceProfile.getSampleRate(),
				integer(safeVoiceOptions.get("sampleRate")), integer(safeTtsOptions.get("sampleRate")),
				ttsConfig == null ? null : ttsConfig.getDefaultSampleRate(), 24000);
		return new AliyunTtsRequest(modelConfig, ttsConfig, voiceProfile, request, safeTtsOptions, safeVoiceOptions,
				safeRequestOptions, apiFamily, request == null ? null : request.getText(),
				modelConfig == null ? null : modelConfig.getModelName(),
				firstText(voiceProfile == null ? null : voiceProfile.getVoiceName(), text(safeVoiceOptions.get("voice")),
						text(safeTtsOptions.get("voice"))),
				format, sampleRate,
				firstDecimal(request == null ? null : request.getSpeed(),
						voiceProfile == null ? null : voiceProfile.getSpeechSpeed(), decimal(safeVoiceOptions.get("speed")),
						decimal(safeTtsOptions.get("speed")), BigDecimal.ONE),
				firstDecimal(voiceProfile == null ? null : voiceProfile.getPitch(), decimal(safeVoiceOptions.get("pitch")),
						decimal(safeTtsOptions.get("pitch"))),
				firstDecimal(voiceProfile == null ? null : voiceProfile.getVolumeGain(),
						decimal(safeVoiceOptions.get("volumeGain")), decimal(safeTtsOptions.get("volumeGain"))),
				firstInt(integer(safeTtsOptions.get("connectTimeoutMs")), DEFAULT_CONNECT_TIMEOUT_MS),
				firstInt(integer(safeTtsOptions.get("readTimeoutMs")), integer(safeTtsOptions.get("timeoutMs")),
						DEFAULT_READ_TIMEOUT_MS),
				firstInt(integer(safeTtsOptions.get("firstAudioTimeoutMs")), DEFAULT_FIRST_AUDIO_TIMEOUT_MS),
				firstText(text(safeTtsOptions.get("workspace")), text(safeVoiceOptions.get("workspace"))),
				firstText(text(safeTtsOptions.get("endpoint")), text(safeTtsOptions.get("websocketUrl")),
						text(safeTtsOptions.get("baseWebsocketUrl"))));
	}

	Map<String, Object> mergedOptions() {
		Map<String, Object> merged = new LinkedHashMap<>();
		merged.putAll(ttsOptions);
		merged.putAll(voiceOptions);
		merged.putAll(requestOptions);
		return merged;
	}

	String contentType() {
		String normalized = StringUtils.hasText(format) ? format.trim().toLowerCase() : "mp3";
		return switch (normalized) {
			case "wav" -> "audio/wav";
			case "ogg", "opus" -> "audio/ogg";
			case "pcm" -> "audio/pcm";
			default -> "audio/mpeg";
		};
	}

	private static Map<String, Object> copy(Map<String, Object> value) {
		return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
	}

	private static String firstText(String... values) {
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

	private static Integer firstInt(Integer... values) {
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

	private static BigDecimal firstDecimal(BigDecimal... values) {
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

	private static String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static Integer integer(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (!StringUtils.hasText(text(value))) {
			return null;
		}
		try {
			return Integer.valueOf(text(value).trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static BigDecimal decimal(Object value) {
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number number) {
			return BigDecimal.valueOf(number.doubleValue());
		}
		if (!StringUtils.hasText(text(value))) {
			return null;
		}
		try {
			return new BigDecimal(text(value).trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
