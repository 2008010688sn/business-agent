/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tts.client.aliyun;

import com.sn68.agent.dataagent.dto.tts.AudioSpeechResult;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Sambert 引擎实现：调用 DashScope Sambert 语音合成接口。
 */
@Component
@RequiredArgsConstructor
class SambertAliyunTtsEngine implements AliyunTtsEngine {

	private final AliyunDashScopeTtsGateway gateway;

	@Override
	public AliyunTtsApiFamily family() {
		return AliyunTtsApiFamily.SAMBERT;
	}

	@Override
	public AudioSpeechResult synthesize(AliyunTtsRequest request) {
		byte[] audio = gateway.synthesizeSambert(request);
		if (audio == null || audio.length == 0) {
			throw CheckedException.fail("语音合成失败：阿里 Sambert 响应为空。");
		}
		return AudioSpeechResult.builder().audio(audio).contentType(request.contentType()).format(request.format()).build();
	}

}
