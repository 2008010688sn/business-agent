/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 持久运行时 Step 响应。
 */
@Schema(description = "持久运行时 Step 响应")
public record RuntimeStepResp(

		@Schema(description = "步骤ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long id,

		@Schema(description = "步骤键")
		String stepKey,

		@Schema(description = "步骤展示名称")
		String stepName,

		@Schema(description = "能力句柄")
		String capabilityHandle,

		@Schema(description = "依赖的上游步骤键清单（JSON 数组）")
		String dependsOn,

		@Schema(description = "步骤状态")
		String state,

		@Schema(description = "已尝试次数")
		Integer attemptCount,

		@Schema(description = "最大尝试次数")
		Integer maxAttempts,

		@Schema(description = "产出 Artifact ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long outputArtifactId,

		@Schema(description = "错误编码")
		String errorCode,

		@Schema(description = "错误信息")
		String errorMessage,

		@Schema(description = "开始时间")
		Instant startedAt,

		@Schema(description = "结束时间")
		Instant finishedAt) {

	public static RuntimeStepResp from(AgentRuntimeStep step) {
		if (step == null) {
			return null;
		}
		return new RuntimeStepResp(step.getId(), step.getStepKey(), step.getStepName(), step.getCapabilityHandle(),
				step.getDependsOn(), step.getState(), step.getAttemptCount(), step.getMaxAttempts(),
				step.getOutputArtifactId(), step.getErrorCode(), step.getErrorMessage(), step.getStartedAt(),
				step.getFinishedAt());
	}

}
