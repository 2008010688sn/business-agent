/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.entity;

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
 * Agent 自优化发布记录。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_opt_release")
@Schema(description = "Agent 自优化发布记录")
public class DataAgentOptRelease extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "租户编码")
	private String tenantCode;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "优化实验ID")
	private Long experimentId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "候选ID")
	private Long candidateId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "来源发布记录ID")
	private Long sourceReleaseId;

	/**
	 * DataAgent 路径新记录恒为 null（活体 publish，不再写 data_agent_release）。
	 * 数字员工路径存放本次 Seal 的 {@code digital_employee_release.id}。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "数字员工 Release ID；DataAgent 路径为空")
	private Long productionReleaseId;

	@Schema(description = "操作类型")
	private String actionType;

	@Schema(description = "发布状态")
	private String status;

	@Schema(description = "发布前哈希")
	private String beforeHash;

	@Schema(description = "发布后哈希")
	private String afterHash;

	@Schema(description = "操作人ID")
	private String operatorId;

	@Schema(description = "操作原因")
	private String reason;

	@Schema(description = "审批记录JSON")
	private String approvalJson;

}
