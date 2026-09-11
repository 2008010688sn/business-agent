/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime;

/**
 * 实时语音单轮结果：承载转写文本、应答文本与合成音频。
 */
public record RealtimeVoiceTurnResult(String turnId, long sequence, String runtimeRequestId, String transcript,
		String answerText, byte[] audio, String audioContentType, String audioFormat) {
}
