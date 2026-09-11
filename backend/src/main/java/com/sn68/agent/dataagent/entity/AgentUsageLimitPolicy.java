/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Agent用量Limit策略实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_usage_limit_policy")
@Schema(description = "DataAgent 用量限制策略")
public class AgentUsageLimitPolicy extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "类型")
	private String scopeType;

	@Schema(description = "scopeId字段")
	private String scopeId;

	@Schema(description = "名称")
	private String userNickName;

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "Agent名称")
	private String agentName;

	@Schema(description = "模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "策略类型")
	private String policyType;

	@Schema(description = "类型")
	private String windowType;

	@Schema(description = "windowSeconds字段")
	private Long windowSeconds;

	@Schema(description = "限制字段")
	private Long limitValue;

	@Schema(description = "warnThresholdRatio字段")
	private Double warnThresholdRatio;

	@Schema(description = "action字段")
	private String action;

	@Schema(description = "是否启用")
	private Boolean enabled;

	@Schema(description = "描述")
	private String description;

}
