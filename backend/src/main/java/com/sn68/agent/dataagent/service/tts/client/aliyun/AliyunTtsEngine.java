/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tts.client.aliyun;

import com.sn68.agent.dataagent.dto.tts.AudioSpeechResult;

/**
 * 阿里 TTS 引擎抽象：单一 API 家族的语音合成实现契约。
 */
interface AliyunTtsEngine {

	/**
	 * 本引擎所属的阿里 TTS API 家族，用于按请求路由。
	 */
	AliyunTtsApiFamily family();

	/**
	 * 执行一次语音合成并返回音频结果，失败抛业务异常。
	 */
	AudioSpeechResult synthesize(AliyunTtsRequest request);

}
