/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 写工具 ASK 确认决定。绑定 toolCallId + 参数指纹，走现有审批表一次性消费。
 */
@Schema(description = "写工具 ASK 确认决定")
public record ToolConfirmDecisionReq(

		@Schema(description = "工具调用 ID", requiredMode = Schema.RequiredMode.REQUIRED)
		String toolCallId,

		@Schema(description = "工具名")
		String toolName,

		@Schema(description = "参数指纹（与卡片 metadata 一致）", requiredMode = Schema.RequiredMode.REQUIRED)
		String paramFingerprint,

		@Schema(description = "2.0 RequireUserConfirmEvent.replyId")
		String replyId,

		@Schema(description = "审批意见（驳回时必填）")
		String comment) {
}
