/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.entity;

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
 * 持久运行时权威运行记录实体。
 *
 * <p>与遥测表 agent_orchestration_run 并存：遥测表继续记录耗时与结果，本表是运行状态的权威来源，
 * 承载 CAS（state_version）、租约（lease_owner/lease_until/fence_token）与取消纪元（cancellation_epoch）。</p>
 *
 * <p><b>PR-1:</b> 幂等键由 (tenant_id, workspace_id, client_request_id) 改为
 * (tenant_id, owner_type, owner_id, client_request_id)，移除 workspace 隔离语义，改用 owner 维度（任务归属数字员工或对话发起用户）。</p>
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_runtime_run")
@Schema(description = "持久运行时权威运行记录")
public class AgentRuntimeRun extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "所属租户 ID")
	private String tenantId;
	
	@Schema(description = "运行主体类型：DIGITAL_EMPLOYEE / CALLER / PLATFORM")
	private String ownerType;
	
	@Schema(description = "运行主体 ID（ownerType 为 DIGITAL_EMPLOYEE 时为 digitalEmployeeId，CALLER 时为用户 ID）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long ownerId;
	
	/** PR-1: tenant_id 双写载体（Long 序列化为字符串），用于过渡期查询与回查 */
	@Schema(description = "租户 ID 字符串形式（与 tenantId 一致）")
	private String tenantIdStr;
	
	@Schema(description = "所属数字员工 ID（可空，调用链路直接归属该员工时填充）", nullable = true)
	@JsonSerialize(using = ToStringSerializer.class)
	private Long digitalEmployeeId;

	@Schema(description = "DataAgent 发布版本ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long releaseId;

	@Schema(description = "运行的 DataAgent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "调用方请求ID，配合 owner 维度（ownerType + ownerId）做幂等创建")
	private String clientRequestId;

	@Schema(description = "会话ID")
	private String threadId;

	@Schema(description = "运行时请求ID，用于与内存注册表/遥测链路关联")
	private String runtimeRequestId;

	@Schema(description = "来源遥测表 agent_orchestration_run.id，双写迁移期关联用")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long sourceRunId;

	@Schema(description = "触发来源：API、CHAT、IM、SCHEDULE、EVENT、LEGACY_ORCHESTRATION")
	private String triggerSource;

	@Schema(description = "运行模式：CHAT、DIRECT、AGENT_LOOP、ORCHESTRATION、FLOW")
	private String runMode;

	@Schema(description = "用户问题或任务描述")
	private String query;

	@Schema(description = "运行状态")
	private String state;

	@Schema(description = "状态版本号，CAS 更新依据")
	private Long stateVersion;

	@Schema(description = "当前租约持有者（节点标识）")
	private String leaseOwner;

	@Schema(description = "租约到期时间")
	private Instant leaseUntil;

	@Schema(description = "栅栏令牌，租约获取/接管时递增")
	private Long fenceToken;

	@Schema(description = "取消纪元，取消请求递增")
	private Long cancellationEpoch;

	@Schema(description = "绝对截止时间")
	private Instant deadlineAt;

	@Schema(description = "最终回答（仅展示用途）")
	private String finalAnswer;

	@Schema(description = "错误编码")
	private String errorCode;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "开始时间")
	private Instant startedAt;

	@Schema(description = "结束时间")
	private Instant finishedAt;

}
