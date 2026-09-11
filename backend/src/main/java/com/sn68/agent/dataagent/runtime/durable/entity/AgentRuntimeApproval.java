/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 持久运行时审批实体。审批过期、参数变化、权限变化后必须重新审批（requestDigest 变化即失效）。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_runtime_approval", autoResultMap = true)
@Schema(description = "持久运行时审批记录")
public class AgentRuntimeApproval extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID，必填；解析不到租户时审批链路失败关闭")
	private String tenantId;

	@Schema(description = "PR-1 遗留兼容列：workspace 域已拆除，恒落 0；审批作用域由 approvalKey 的 owner/run 段编码，列清理随后续 PR DDL")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long workspaceId;

	@Schema(description = "所属运行ID，0 表示无关联运行的管理动作")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long runId;

	@Schema(description = "关联步骤键")
	private String stepKey;

	@Schema(description = "审批幂等键")
	private String approvalKey;

	@Schema(description = "风险等级：LOW、MEDIUM、HIGH")
	private String riskLevel;

	@Schema(description = "审批状态：PENDING、APPROVED、REJECTED、EXPIRED、CANCELLED、CONSUMED（已消费，用后失效）")
	private String state;

	@Schema(description = "审批时的请求参数摘要，参数变化后旧审批失效")
	private String requestDigest;

	@Schema(description = "审批展示负载 JSON（脱敏后的操作说明）")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String payload;

	@Schema(description = "发起人ID")
	private String requestedBy;

	@Schema(description = "审批人ID")
	private String approver;

	@Schema(description = "审批决定时间")
	private Instant decidedAt;

	@Schema(description = "审批过期时间")
	private Instant expiresAt;

	@Schema(description = "审批意见")
	private String decisionComment;

}
