/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.employee.dto;

import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeArtifactResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeStepResp;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 数字员工运行详情：履历门面，不要求 runtime-run 权限码。
 */
@Schema(description = "数字员工运行详情")
public record EmployeeRunDetailResp(

		@Schema(description = "运行摘要")
		RuntimeRunResp run,

		@Schema(description = "最终回答（空则回退 single-turn-answer 产物）")
		String finalAnswer,

		@Schema(description = "步骤清单")
		List<RuntimeStepResp> steps,

		@Schema(description = "当前最大事件 seq")
		Long latestSeq,

		@Schema(description = "产物摘要")
		List<RuntimeArtifactResp> artifacts,

		@Schema(description = "当前用户对本运行的反馈，未评为空")
		EmployeeRunFeedbackResp feedback) {
}
