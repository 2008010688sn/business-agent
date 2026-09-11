/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tts.client.aliyun;

import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * 阿里 TTS API 家族枚举：区分 Sambert、CosyVoice、Qwen 等接入形态。
 */
enum AliyunTtsApiFamily {

	SAMBERT("sambert"),

	COSYVOICE("cosyvoice"),

	QWEN_TTS("qwen-tts"),

	QWEN_TTS_REALTIME("qwen-tts-realtime");

	private final String code;

	AliyunTtsApiFamily(String code) {
		this.code = code;
	}

	String code() {
		return code;
	}

	static AliyunTtsApiFamily resolve(String apiFamily, String modelName) {
		String normalizedFamily = normalize(apiFamily);
		if ("sambert".equals(normalizedFamily)) {
			return SAMBERT;
		}
		if ("cosyvoice".equals(normalizedFamily) || "cosy-voice".equals(normalizedFamily)) {
			return COSYVOICE;
		}
		if ("qwen-tts-realtime".equals(normalizedFamily) || "qwen_tts_realtime".equals(normalizedFamily)) {
			return QWEN_TTS_REALTIME;
		}
		if ("qwen-tts".equals(normalizedFamily) || "qwen_tts".equals(normalizedFamily)) {
			return QWEN_TTS;
		}
		String normalizedModel = normalize(modelName);
		if (normalizedModel.startsWith("sambert-")) {
			return SAMBERT;
		}
		if (normalizedModel.startsWith("cosyvoice") || normalizedModel.startsWith("cosy-voice")) {
			return COSYVOICE;
		}
		if (normalizedModel.startsWith("qwen-tts-realtime")) {
			return QWEN_TTS_REALTIME;
		}
		if (normalizedModel.startsWith("qwen-tts")) {
			return QWEN_TTS;
		}
		return null;
	}

	private static String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
	}

}
