/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 优化候选保存请求。
 */
@Data
@Schema(description = "优化候选保存请求")
public class OptCandidateRequest {

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

}
