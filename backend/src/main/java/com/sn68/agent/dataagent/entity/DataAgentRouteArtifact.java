/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Route semantics and vector metadata snapshot for one executable target.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_agent_route_artifact", autoResultMap = true)
public class DataAgentRouteArtifact extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 所属路由档案ID。 */
	private Long profileId;

	/** 租户ID。 */
	private String tenantId;

	/** 路由目标类型（SKILL/AGENT等）。 */
	private String targetType;

	/** 目标ID。 */
	private Long targetId;

	/** 目标版本ID。 */
	private Long targetVersionId;

	/** 目标唯一键（profile+租户内唯一）。 */
	private String targetKey;

	/** Skill类型（冗余快照）。 */
	private String skillKind;

	/** 执行模式（冗余快照）。 */
	private String executionMode;

	/** 展示名称（冗余快照）。 */
	private String displayName;

	/** 描述（冗余快照）。 */
	private String description;

	/** 归一化后的路由规则（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String normalizedRules;

	/** 风险等级。 */
	private String riskLevel;

	/** 源内容校验和（源变更判定重建）。 */
	private String sourceChecksum;

	/** 归一化产物内容校验和。 */
	private String contentChecksum;

	/** embedding 指纹（模型/维度变更判定重建）。 */
	private String embeddingFingerprint;

	/** 向量维度。 */
	private Integer embeddingDimension;

	/** 向量库文档ID。 */
	private String vectorDocumentId;

	/** 产物状态（READY/FAILED等）。 */
	private String status;

	/** 失败码。 */
	private String failureCode;

}
