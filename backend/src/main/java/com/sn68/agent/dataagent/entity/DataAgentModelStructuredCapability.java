/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Persisted structured-output capability for one model task profile and protocol.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_model_structured_capability")
public class DataAgentModelStructuredCapability extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 所属租户ID。 */
	private String tenantId;

	/** 模型配置ID。 */
	private Long modelConfigId;

	/** 任务画像（探测所针对的结构化输出场景）。 */
	private String taskProfile;

	/** 结构化输出协议（STRICT_JSON_SCHEMA/JSON_OBJECT等）。 */
	private String protocol;

	/** 模型指纹（模型变更后缓存失效）。 */
	private String modelFingerprint;

	/** 编译器版本（schema 编译逻辑版本）。 */
	private String compilerVersion;

	/** 探针版本（探测逻辑版本）。 */
	private String probeVersion;

	/** 能力状态（SUPPORTED/UNSUPPORTED/PENDING等）。 */
	private String state;

	/** 探测认领租约标识（防止多实例并发探测）。 */
	private String claimLease;

	/** 租约到期时间。 */
	private Instant leaseUntil;

	/** 最近探测时间。 */
	private Instant checkedAt;

	/** 能力记录过期时间（过期需重探）。 */
	private Instant expiresAt;

	/** 下次允许探测时间（失败退避）。 */
	private Instant nextAttemptAt;

	/** 已探测次数。 */
	private Integer attemptCount;

	/** 探测耗时（毫秒）。 */
	private Long latencyMs;

	/** 探测消耗的平台 token 数。 */
	private Long platformTokens;

	/** 失败码。 */
	private String failureCode;

}
