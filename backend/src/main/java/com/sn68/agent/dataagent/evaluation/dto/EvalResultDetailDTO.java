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
 * 评估结果详情。
 */
@Builder
@Schema(description = "评估结果详情")
public record EvalResultDetailDTO(

		@Schema(description = "评估结果") DataAgentEvalCaseResult result,

		@Schema(description = "会话诊断详情，源数据请使用 Trace 接口按权限加载") DataChatTurnDetailResp diagnostics) {
}
