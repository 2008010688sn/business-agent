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
 * DataAgent可见性策略实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_visibility_policy")
@Schema(description = "DataAgent 用户对话页可见性策略")
public class DataAgentVisibilityPolicy extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "conversationScope字段")
	private String conversationScope;

	@Schema(description = "catalogScope字段")
	private String catalogScope;

	@Schema(description = "模式字段")
	private String applyMode;

	@Schema(description = "模式字段")
	private String approvalMode;

	@Schema(description = "编码")
	private String workflowFlowCode;

	@Schema(description = "级别字段")
	private String riskLevel;

	@Schema(description = "默认标识字段")
	private Integer defaultGrantDays;

	@Schema(description = "配置")
	private String approverConfigJson;

	@Schema(description = "状态")
	private String status;

}
