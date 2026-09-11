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
 * DataAgent可见性申请实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_visibility_application")
@Schema(description = "DataAgent 用户对话页可见性申请")
public class DataAgentVisibilityApplication extends SuperEntity<Long> {

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

	@Schema(description = "applicantUserId字段")
	private String applicantUserId;

	@Schema(description = "名称")
	private String applicantNickName;

	@Schema(description = "reason字段")
	private String reason;

	@Schema(description = "grantDays字段")
	private Integer grantDays;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "模式字段")
	private String approvalMode;

	@Schema(description = "approverUserId字段")
	private String approverUserId;

	@Schema(description = "名称")
	private String approverNickName;

	@Schema(description = "approvalComment字段")
	private String approvalComment;

	@Schema(description = "编码")
	private String workflowFlowCode;

	@Schema(description = "workflowInstanceId字段")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long workflowInstanceId;

	@Schema(description = "状态")
	private String workflowStatus;

	@Schema(description = "编码")
	private String businessCode;

	@Schema(description = "时间")
	private Instant submitTime;

	@Schema(description = "时间")
	private Instant finishTime;

}
