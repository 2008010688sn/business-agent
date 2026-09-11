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
 * Agent 自优化候选版本。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_opt_candidate")
@Schema(description = "Agent 自优化候选版本")
public class DataAgentOptCandidate extends SuperEntity<Long> {

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

	@Schema(description = "候选名称")
	private String candidateName;

	@Schema(description = "目标类型")
	private String targetType;

	@Schema(description = "目标ID")
	private String targetId;

	@Schema(description = "补丁结构版本")
	private String patchSchemaVersion;

	@Schema(description = "目标配置版本")
	private String targetVersion;

	@Schema(description = "变更前哈希")
	private String beforeHash;

	@Schema(description = "变更后哈希")
	private String afterHash;

	@Schema(description = "变更原因")
	private String changeReason;

	@Schema(description = "预期影响")
	private String expectedImpact;

	@Schema(description = "候选补丁JSON")
	private String patchJson;

	@Schema(description = "变更前快照JSON")
	private String beforeSnapshotJson;

	@Schema(description = "回滚补丁JSON")
	private String rollbackPatchJson;

	@Schema(description = "风险等级")
	private String riskLevel;

	@Schema(description = "生成来源")
	private String generatedBy;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "候选沙箱运行ID")
	private Long sandboxRunId;

	@Schema(description = "门禁结果JSON")
	private String gateResultJson;

	@Schema(description = "候选状态")
	private String status;

}
