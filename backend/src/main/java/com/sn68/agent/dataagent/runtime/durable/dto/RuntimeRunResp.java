/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 持久运行时 Run 响应。
 */
@Schema(description = "持久运行时 Run 响应")
public record RuntimeRunResp(

		@Schema(description = "运行ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long id,

		@Schema(description = "运行主体类型：DIGITAL_EMPLOYEE / CALLER / PLATFORM")
		String ownerType,
		
		@Schema(description = "运行主体 ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long ownerId,
		
		@Schema(description = "数字员工 ID，空表示非数字员工发起")
		@JsonSerialize(using = ToStringSerializer.class)
		Long digitalEmployeeId,
		
		@Schema(description = "owner 对应的 Release ID（数字员工为 digital_employee_release.id；普通智能体不再使用）")
		@JsonSerialize(using = ToStringSerializer.class)
		Long releaseId,

		@Schema(description = "DataAgent ID（数字员工运行时为合成代理，等于员工 ID）")
		@JsonSerialize(using = ToStringSerializer.class)
		Long agentId,

		@Schema(description = "调用方请求ID")
		String clientRequestId,

		@Schema(description = "会话ID")
		String threadId,

		@Schema(description = "运行时请求ID")
		String runtimeRequestId,

		@Schema(description = "触发来源")
		String triggerSource,

		@Schema(description = "运行模式")
		String runMode,

		@Schema(description = "用户问题或任务描述")
		String query,

		@Schema(description = "运行状态")
		String state,

		@Schema(description = "状态版本号")
		Long stateVersion,

		@Schema(description = "取消纪元")
		Long cancellationEpoch,

		@Schema(description = "绝对截止时间")
		Instant deadlineAt,

		@Schema(description = "错误编码")
		String errorCode,

		@Schema(description = "错误信息")
		String errorMessage,

		@Schema(description = "开始时间")
		Instant startedAt,

		@Schema(description = "结束时间")
		Instant finishedAt,

		@Schema(description = "创建时间")
		Instant createTime,

		@Schema(description = "发起人用户 ID（审计谁触发；任务调度可空）")
		String initiatorUserId,

		@Schema(description = "发起人名称")
		String initiatorUserName,

		@Schema(description = "执行主体 Principal ID（sp_ 前缀；CALLER 运行为空）")
		String executionPrincipalId,

		@Schema(description = "决策主体类别：DIGITAL_EMPLOYEE / CALLER / PLATFORM")
		String subjectKind,

		@Schema(description = "owner 对应 Release 的 spec_hash（内容指纹）")
		String specHash) {

	public static RuntimeRunResp from(AgentRuntimeRun run) {
		return from(run, null, null);
	}

	public static RuntimeRunResp from(AgentRuntimeRun run, String executionPrincipalId, String specHash) {
		if (run == null) {
			return null;
		}
		return new RuntimeRunResp(run.getId(), run.getOwnerType(), run.getOwnerId(), run.getDigitalEmployeeId(),
				run.getReleaseId(), run.getAgentId(),
				run.getClientRequestId(), run.getThreadId(), run.getRuntimeRequestId(), run.getTriggerSource(),
				run.getRunMode(), run.getQuery(), run.getState(), run.getStateVersion(), run.getCancellationEpoch(),
				run.getDeadlineAt(), run.getErrorCode(), run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt(),
				run.getCreateTime(), run.getCreateBy(), run.getCreateName(), executionPrincipalId,
				run.getOwnerType(), specHash);
	}

}
