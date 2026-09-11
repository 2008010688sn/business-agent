/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tts.client.aliyun;

import java.util.Map;

/**
 * 阿里 DashScope TTS 网关抽象：屏蔽 SDK 细节，按请求返回合成音频。
 */
interface AliyunDashScopeTtsGateway {

	/**
	 * Sambert 语音合成，返回音频字节。
	 */
	byte[] synthesizeSambert(AliyunTtsRequest request);

	/**
	 * CosyVoice（TTS v2）语音合成，返回音频字节。
	 */
	byte[] synthesizeTtsV2(AliyunTtsRequest request);

	/**
	 * Qwen-TTS 语音合成，返回音频字节或可下载的音频 URL。
	 */
	QwenTtsAudioResult synthesizeQwenTts(AliyunTtsRequest request);

	/**
	 * Qwen-TTS 实时接口流式合成，聚合完整音频后返回。
	 */
	byte[] synthesizeQwenRealtime(AliyunTtsRequest request);

	record QwenTtsAudioResult(byte[] audio, String url, Map<String, Object> metadata) {
	}

}
