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
 * 持久运行时外部副作用调用实体。
 *
 * <p>生命周期：DISPATCH_INTENT → INVOCATION_SENT → SUCCESS/FAILED/OUTCOME_UNKNOWN → RECONCILING。
 * (runId, idempotencyKey) 唯一，保证同一写操作不重复下发。</p>
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_runtime_invocation", autoResultMap = true)
@Schema(description = "持久运行时外部副作用调用记录")
public class AgentRuntimeInvocation extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "所属运行ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long runId;

	@Schema(description = "所属步骤ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long stepId;

	@Schema(description = "所属尝试ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long attemptId;

	@Schema(description = "幂等键，同一 run 内唯一")
	private String idempotencyKey;

	@Schema(description = "被调用能力句柄")
	private String capabilityHandle;

	@Schema(description = "调用类型：TOOL、SKILL、FLOW、AGENT、MCP、HTTP")
	private String invocationType;

	@Schema(description = "调用状态")
	private String state;

	@Schema(description = "状态版本号，CAS 更新依据")
	private Long stateVersion;

	@Schema(description = "请求参数摘要（不落原始敏感参数）")
	private String requestDigest;

	@Schema(description = "响应内容摘要")
	private String responseDigest;

	@Schema(description = "外部副作用凭证 JSON（订单号、调度单号等业务回执）")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String sideEffectReceipt;

	@Schema(description = "错误编码")
	private String errorCode;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "外部请求实际发出时间")
	private Instant sentAt;

	@Schema(description = "终态时间")
	private Instant completedAt;

	@Schema(description = "对账完成时间")
	private Instant reconciledAt;

	@Schema(description = "授权决策ID（PEP 决策审计列，首次判定后不再覆盖）")
	private String decisionId;

	@Schema(description = "参与判定的策略哈希（PEP 决策审计列）")
	private String policyHash;

	@Schema(description = "决策主体类别（CALLER/DIGITAL_EMPLOYEE，PEP 决策审计列）")
	private String subjectKind;

	@Schema(description = "PDP 原因码（PEP 决策审计列）")
	private String reasonCode;

}
