/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeApproval;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 持久运行时审批响应。
 */
@Schema(description = "持久运行时审批响应")
public record RuntimeApprovalResp(

		@Schema(description = "审批ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long id,

		@Schema(description = "所属运行ID，0 表示无关联运行")
		@JsonSerialize(using = ToStringSerializer.class)
		Long runId,

		@Schema(description = "关联步骤键")
		String stepKey,

		@Schema(description = "审批幂等键（owner 作用域 + 运行 + 能力编码 + 参数指纹）")
		String approvalKey,

		@Schema(description = "风险等级")
		String riskLevel,

		@Schema(description = "审批状态")
		String state,

		@Schema(description = "请求参数摘要，参数变化后旧审批失效")
		String requestDigest,

		@Schema(description = "审批展示负载 JSON（脱敏后的操作说明）")
		String payload,

		@Schema(description = "发起人ID")
		String requestedBy,

		@Schema(description = "审批人ID")
		String approver,

		@Schema(description = "审批决定时间")
		Instant decidedAt,

		@Schema(description = "审批过期时间")
		Instant expiresAt,

		@Schema(description = "审批意见")
		String decisionComment,

		@Schema(description = "创建时间")
		Instant createTime) {

	public static RuntimeApprovalResp from(AgentRuntimeApproval approval) {
		if (approval == null) {
			return null;
		}
		return new RuntimeApprovalResp(approval.getId(), approval.getRunId(),
				approval.getStepKey(),
				approval.getApprovalKey(), approval.getRiskLevel(), approval.getState(), approval.getRequestDigest(),
				approval.getPayload(), approval.getRequestedBy(), approval.getApprover(), approval.getDecidedAt(),
				approval.getExpiresAt(), approval.getDecisionComment(), approval.getCreateTime());
	}

}
