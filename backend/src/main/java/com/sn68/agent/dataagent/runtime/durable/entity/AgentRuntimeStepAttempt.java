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
 * 持久运行时步骤执行尝试实体（每次认领执行产生一条，attemptNo 同一步骤内唯一）。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_runtime_step_attempt")
@Schema(description = "持久运行时步骤执行尝试记录")
public class AgentRuntimeStepAttempt extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "所属运行ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long runId;

	@Schema(description = "所属步骤ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long stepId;

	@Schema(description = "尝试序号，从 1 开始")
	private Integer attemptNo;

	@Schema(description = "尝试状态：RUNNING、SUCCEEDED、FAILED、CANCELLED、TIMED_OUT")
	private String state;

	@Schema(description = "发起该尝试的租约持有者（节点标识）")
	private String leaseOwner;

	@Schema(description = "该尝试使用的栅栏令牌")
	private Long fenceToken;

	@Schema(description = "错误编码")
	private String errorCode;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "开始时间")
	private Instant startedAt;

	@Schema(description = "结束时间")
	private Instant finishedAt;

}
