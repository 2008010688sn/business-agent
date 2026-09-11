/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime.client;

/**
 * 实时语音平台Gateway服务契约。
 */
public interface RealtimeVoiceProviderGateway {

	/**
	 * 处理实时语音平台Gateway。
	 */
	boolean supports(RealtimeVoiceProviderRequest request);

	/**
	 * 执行实时语音平台Gateway。
	 */
	RealtimeVoiceProviderSession open(RealtimeVoiceProviderRequest request);

}
