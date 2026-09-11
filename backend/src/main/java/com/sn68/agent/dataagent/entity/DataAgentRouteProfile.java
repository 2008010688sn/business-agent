/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import java.io.Serial;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Platform-level immutable routing profile after activation.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_route_profile")
public class DataAgentRouteProfile extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 所属租户ID。 */
	private String tenantId;

	/** 档案名称。 */
	private String profileName;

	/** 档案状态（DRAFT/BUILDING/READY/FAILED/ACTIVE/RETIRED）。 */
	private String status;

	/** 路由消歧模型配置ID。 */
	private Long routeModelConfigId;

	/** 路由模型能力探测状态（SUPPORTED/STALE等）。 */
	private String routeModelProbeState;

	/** 路由模型结构化输出协议。 */
	private String routeModelProtocol;

	/** 路由模型指纹。 */
	private String routeModelFingerprint;

	/** 路由模型探针版本。 */
	private String routeModelProbeVersion;

	/** 路由模型最近探测时间。 */
	private Instant routeModelCheckedAt;

	/** 路由模型探测耗时（毫秒）。 */
	private Long routeModelLatencyMs;

	/** 路由模型探测失败码。 */
	private String routeModelFailureCode;

	/** 路由模型运行时是否就绪。 */
	private Boolean routeModelRuntimeReady;

	/** embedding 模型配置ID。 */
	private Long embeddingModelConfigId;

	/** embedding 能力探测状态。 */
	private String embeddingProbeState;

	/** embedding 最近探测时间。 */
	private Instant embeddingCheckedAt;

	/** embedding 探测耗时（毫秒）。 */
	private Long embeddingLatencyMs;

	/** embedding 探针版本。 */
	private String embeddingProbeVersion;

	/** embedding 探测失败码。 */
	private String embeddingFailureCode;

	/** 词法命中自动选择是否启用。 */
	private Boolean lexicalAutoSelectEnabled;

	/** 语义召回是否启用。 */
	private Boolean semanticRecallEnabled;

	/** 语义命中自动选择是否启用。 */
	private Boolean semanticAutoSelectEnabled;

	/** 模型消歧是否启用。 */
	private Boolean modelDisambiguationEnabled;

	/** 词法命中最低分。 */
	private Integer lexicalMinScore;

	/** 词法首选与次选最小分差。 */
	private Integer lexicalMinGap;

	/** 向量召回阈值。 */
	private BigDecimal vectorRecallThreshold;

	/** 向量自动选择阈值。 */
	private BigDecimal vectorAutoSelectThreshold;

	/** 向量首选与次选最小分差。 */
	private BigDecimal vectorMinGap;

	/** 模型消歧置信度阈值。 */
	private BigDecimal modelConfidenceThreshold;

	/** embedding 指纹（构建产物所用）。 */
	private String embeddingFingerprint;

	/** 向量维度。 */
	private Integer embeddingDimension;

	/**
	 * Legacy strict-schema result retained for one compatibility release only.
	 */
	private String strictSchemaStatus;

	/**
	 * Legacy combined probe timestamp retained for one compatibility release only.
	 */
	private Instant probeCheckedAt;

	/** 产物构建状态（NOT_BUILT/RUNNING/READY/FAILED）。 */
	private String buildStatus;

	/** 构建目标总数。 */
	private Integer buildTotal;

	/** 构建成功数。 */
	private Integer buildReady;

	/** 构建失败数。 */
	private Integer buildFailed;

	/** 最近一次错误码。 */
	private String lastErrorCode;

	/** 乐观锁修订号（条件更新的 CAS 依据）。 */
	private Long revision;

}
