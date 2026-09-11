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
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * DataAgent可见性授权实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_visibility_grant")
@Schema(description = "DataAgent 用户对话页可见性授权")
public class DataAgentVisibilityGrant extends SuperEntity<Long> {

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

	@Schema(description = "Agent名称")
	private String agentName;

	@Schema(description = "类型")
	private String subjectType;

	@Schema(description = "subjectId字段")
	private String subjectId;

	@Schema(description = "名称")
	private String subjectName;

	@Schema(description = "类型来源字段")
	private String sourceType;

	@Schema(description = "applicationId字段")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long applicationId;

	@Schema(description = "时间")
	private Instant expireTime;

	@Schema(description = "状态")
	private String status;

}
