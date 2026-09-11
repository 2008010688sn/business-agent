/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型配置Summary数据传输对象。
 */
@Schema(description = "模型配置Summary数据传输对象")
public record ModelConfigSummaryResp(
		@Schema(description = "数量总数字段") Long totalCount,
		@Schema(description = "数量") Long activeCount,
		@Schema(description = "数量") Long chatCount,
		@Schema(description = "数量") Long embeddingCount,
		@Schema(description = "数量音频字段") Long audioCount,
		@Schema(description = "数量") Long ttsCount,
		@Schema(description = "数量时间语音字段") Long realtimeVoiceCount
) {
}
