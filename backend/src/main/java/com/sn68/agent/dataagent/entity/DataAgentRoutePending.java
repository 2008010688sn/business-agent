/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Server-owned, single-use continuation state for business interactions. */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_agent_route_pending", autoResultMap = true)
public class DataAgentRoutePending extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 租户ID。 */
	private String tenantId;

	/** 归属Agent ID（消费时校验归属）。 */
	private Long ownerAgentId;

	/** 会话ID。 */
	private Long sessionId;

	/** 用户ID。 */
	private String userId;

	/** 会话线程ID。 */
	private String threadId;

	/** 创建本续接的运行请求ID。 */
	private String runtimeRequestId;

	/** 父编排运行ID（编排场景）。 */
	private Long parentRunId;

	/** 父编排步骤ID（编排场景）。 */
	private Long parentStepId;

	/** 执行状态（CLAIMED 及终态，见 RoutePendingService 常量）。 */
	private String executionState;

	/** 执行结果引用（指向结果存储位置）。 */
	private String resultReference;

	/** 续接令牌哈希（不落原文，单次消费凭据）。 */
	private String tokenHash;

	/** 交互类型（澄清/确认等）。 */
	private String interactionType;

	/** 记录状态（PENDING/CONSUMED/EXPIRED）。 */
	private String status;

	/** 澄清轮次（限制多轮澄清次数）。 */
	private Integer clarificationRound;

	/** 用户原始问题。 */
	private String originalQuery;

	/** 向用户展示的提示文案。 */
	private String prompt;

	/** 可回传前端的公开载荷（JSON，不含敏感路由细节）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String publicPayload;

	/** 路由决策快照（JSON，恢复时免重路由）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String routeSnapshot;

	/** 过期时间。 */
	private Instant expiresAt;

	/** 消费时间。 */
	private Instant consumedAt;

	/** 消费方运行请求ID。 */
	private String consumedRuntimeRequestId;

}
