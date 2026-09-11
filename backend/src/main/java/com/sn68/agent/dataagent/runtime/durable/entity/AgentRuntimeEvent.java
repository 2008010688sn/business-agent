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
 * 持久运行时事件实体。run 内 seq 单调递增，SSE 断线后按 afterSeq 回放，无遗漏、无乱序、无重复。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_runtime_event", autoResultMap = true)
@Schema(description = "持久运行时事件")
public class AgentRuntimeEvent extends SuperEntity<Long> {

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

	@Schema(description = "run 内单调递增序号，从 1 开始")
	private Long seq;

	@Schema(description = "事件幂等键，同一 run 内唯一")
	private String eventKey;

	@Schema(description = "事件类型")
	private String eventType;

	@Schema(description = "关联步骤键，运行级事件为空")
	private String stepKey;

	@Schema(description = "事件负载 JSON")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String payload;

	@Schema(description = "事件发生时间")
	private Instant occurredAt;

	@Schema(description = "授权决策ID（PEP 影子决策贯穿标识，非授权决策事件为空）")
	private String decisionId;

	@Schema(description = "参与判定的策略哈希（决策审计列，非授权决策事件为空）")
	private String policyHash;

	@Schema(description = "决策主体类别（CALLER/DIGITAL_EMPLOYEE，决策审计列）")
	private String subjectKind;

	@Schema(description = "PDP 原因码（拒绝原因传播，决策审计列）")
	private String reasonCode;

	@Schema(description = "决策明细日志 JSON（original/shadow/comparison 等影子比对字段，供差异报告消费）")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String detailedDecisionLog;

}
