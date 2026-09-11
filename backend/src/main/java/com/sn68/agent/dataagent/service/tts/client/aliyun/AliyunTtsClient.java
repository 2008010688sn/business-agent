/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tts.client.aliyun;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechReq;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechResult;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import com.sn68.agent.dataagent.entity.TtsVoiceSample;
import com.sn68.agent.dataagent.service.tts.client.TextToSpeechClient;
import com.sn68.agent.dataagent.service.tts.impl.TtsJsonSupport;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 阿里 TTS 客户端入口：按 API 家族选择引擎并执行语音合成。
 */
@Component
@Order(0)
@RequiredArgsConstructor
public class AliyunTtsClient implements TextToSpeechClient {

	private final TtsJsonSupport jsonSupport;

	private final List<AliyunTtsEngine> engines;

	@Override
	public boolean supports(ModelConfigDTO modelConfig, ModelTtsConfig ttsConfig, TtsVoiceProfile voiceProfile) {
		if (modelConfig == null || ttsConfig == null) {
			return false;
		}
		Map<String, Object> options = jsonSupport.readObject(ttsConfig.getOptions());
		String vendor = text(options.get("vendor"));
		AliyunTtsApiFamily family = resolveFamily(modelConfig, options);
		return family != null && (isAliyunProvider(modelConfig.getProvider()) || "aliyun".equalsIgnoreCase(vendor)
				|| "dashscope".equalsIgnoreCase(vendor));
	}

	@Override
	public AudioSpeechResult synthesize(ModelConfigDTO modelConfig, ModelTtsConfig ttsConfig,
			TtsVoiceProfile voiceProfile, TtsVoiceSample voiceSample, AudioSpeechReq request) {
		validate(modelConfig, request);
		Map<String, Object> ttsOptions = jsonSupport.readObject(ttsConfig.getOptions());
		Map<String, Object> voiceOptions = jsonSupport.readObject(voiceProfile.getOptions());
		AliyunTtsApiFamily family = resolveFamily(modelConfig, ttsOptions);
		if (family == null) {
			throw CheckedException.badRequest("阿里 TTS apiFamily 未配置或无法根据模型名称识别。");
		}
		AliyunTtsEngine engine = engineMap().get(family);
		if (engine == null) {
			throw CheckedException.badRequest("未实现的阿里 TTS 协议：" + family.code());
		}
		AliyunTtsRequest aliyunRequest = AliyunTtsRequest.create(modelConfig, ttsConfig, voiceProfile, request,
				ttsOptions, voiceOptions, family);
		if (!StringUtils.hasText(aliyunRequest.voiceName())
				&& family != AliyunTtsApiFamily.SAMBERT) {
			throw CheckedException.badRequest("阿里 TTS 音色 voiceName 不能为空。");
		}
		return engine.synthesize(aliyunRequest);
	}

	private Map<AliyunTtsApiFamily, AliyunTtsEngine> engineMap() {
		Map<AliyunTtsApiFamily, AliyunTtsEngine> map = new EnumMap<>(AliyunTtsApiFamily.class);
		for (AliyunTtsEngine engine : engines) {
			map.put(engine.family(), engine);
		}
		return map;
	}

	private AliyunTtsApiFamily resolveFamily(ModelConfigDTO modelConfig, Map<String, Object> options) {
		return AliyunTtsApiFamily.resolve(text(options.get("apiFamily")),
				modelConfig == null ? null : modelConfig.getModelName());
	}

	private boolean isAliyunProvider(String provider) {
		if (!StringUtils.hasText(provider)) {
			return false;
		}
		String normalized = provider.trim().toLowerCase(Locale.ROOT);
		return "qwen".equals(normalized) || "aliyun".equals(normalized) || "dashscope".equals(normalized);
	}

	private void validate(ModelConfigDTO modelConfig, AudioSpeechReq request) {
		if (modelConfig == null || !StringUtils.hasText(modelConfig.getModelName())) {
			throw CheckedException.badRequest("阿里 TTS 模型名称不能为空。");
		}
		if (!StringUtils.hasText(modelConfig.getApiKey())) {
			throw CheckedException.badRequest("阿里 TTS API Key 不能为空。");
		}
		if (request == null || !StringUtils.hasText(request.getText())) {
			throw CheckedException.badRequest("播报文本不能为空。");
		}
	}

	private String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

}
