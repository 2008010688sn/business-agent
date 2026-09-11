/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import com.sn68.agent.dataagent.dto.chat.DataChatTurnDetailResp;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 评估结果 Trace 分层响应。
 */
@Builder
@Schema(description = "评估结果 Trace 分层响应")
public record EvalResultTraceDTO(

		@Schema(description = "评估结果") DataAgentEvalCaseResult result,

		@Schema(description = "Trace 摘要快照JSON") String traceSnapshotJson,

		@Schema(description = "源 Trace 详情") DataChatTurnDetailResp sourceTrace,

		@Schema(description = "是否可查看源 Trace") Boolean sourceVisible,

		@Schema(description = "源 Trace 提示") String sourceMessage) {
}
