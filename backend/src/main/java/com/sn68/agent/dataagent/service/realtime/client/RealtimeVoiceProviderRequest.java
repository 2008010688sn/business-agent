/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime.client;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.ModelRealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.entity.RealtimeVoiceSession;

/**
 * 实时语音供应商请求参数：聚合会话、模型与语音配置供网关调用。
 */
public record RealtimeVoiceProviderRequest(ModelConfigDTO modelConfig,
		ModelRealtimeVoiceConfigDTO realtimeVoiceConfig, RealtimeVoiceConfigDTO conversationConfig,
		RealtimeVoiceSession session, RealtimeVoiceEventSink eventSink) {
}
