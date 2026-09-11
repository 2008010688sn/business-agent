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

/**
 * Persistent deterministic FLOW instance.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_agent_flow_instance", autoResultMap = true)
public class DataAgentFlowInstance extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 租户ID。 */
	private String tenantId;

	/** 所属Agent ID。 */
	private Long agentId;

	/** 执行的Skill ID。 */
	private Long skillId;

	/** 执行的Skill版本ID（实例生命周期内固定）。 */
	private Long skillVersionId;

	/** Skill编码（冗余，便于排查）。 */
	private String skillCode;

	/** 会话ID。 */
	private Long sessionId;

	/** 会话线程ID。 */
	private String threadId;

	/** 用户ID。 */
	private String userId;

	/** 实例状态（RUNNING/WAITING/COMPLETED/FAILED等）。 */
	private String status;

	/** 当前执行到的节点ID。 */
	private String currentNodeId;

	/** 流程上下文数据（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String contextData;

	/** 等待恢复时的载荷（JSON，含等待类型与提示）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String waitingPayload;

	/** 幂等键（防止同一触发重复建实例）。 */
	private String idempotencyKey;

	/** 乐观锁版本号（advance 的 CAS 条件）。 */
	private Integer lockVersion;

	/**
	 * 最近一次已成功处理的用户输入 SHA-256（十六进制）。等待恢复时收到相同 hash 的输入
	 * 不重复抽取；技术失败会清空该值以允许原文重试。
	 */
	private String inputHash;

	/**
	 * 上下文修订号（与 contextData 内 /runtime/contextRevision 同步落库），
	 * 供恢复与审计判断上下文是否发生过变化。
	 */
	private Long contextRevision;

	/**
	 * 恢复版本号：每次进入 WAITING 递增。恢复请求携带的版本与当前不一致即为过期恢复，拒绝执行。
	 */
	private Integer resumeVersion;

	/** 最近一次推进实例的运行请求ID。 */
	private String lastRuntimeRequestId;

	/** 失败错误码。 */
	private String errorCode;

	/** 失败错误信息。 */
	private String errorMessage;

	/** 等待过期时间（超时未恢复视为过期）。 */
	private Instant expiresAt;

	/** 终态时间。 */
	private Instant finishedAt;

}
