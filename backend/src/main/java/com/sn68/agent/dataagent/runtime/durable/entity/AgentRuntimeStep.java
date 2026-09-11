/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
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
 * 持久运行时步骤实体（事件驱动 DAG 调度的权威状态）。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_runtime_step", autoResultMap = true)
@Schema(description = "持久运行时步骤记录")
public class AgentRuntimeStep extends SuperEntity<Long> {

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

	@Schema(description = "所属计划ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long planId;

	@Schema(description = "计划内步骤键，同一 run 内唯一")
	private String stepKey;

	@Schema(description = "步骤展示名称")
	private String stepName;

	@Schema(description = "能力句柄，同一能力可被多个 stepKey 重复使用")
	private String capabilityHandle;

	@Schema(description = "依赖的上游 stepKey 清单（JSON 数组）")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String dependsOn;

	@Schema(description = "输入端口绑定清单（JSON 数组）")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String inputBindings;

	@Schema(description = "步骤状态")
	private String state;

	@Schema(description = "状态版本号，CAS 更新依据")
	private Long stateVersion;

	@Schema(description = "当前租约持有者（节点标识）")
	private String leaseOwner;

	@Schema(description = "租约到期时间")
	private Instant leaseUntil;

	@Schema(description = "栅栏令牌，租约获取/接管时递增")
	private Long fenceToken;

	@Schema(description = "取消纪元快照")
	private Long cancellationEpoch;

	@Schema(description = "步骤级截止时间")
	private Instant deadlineAt;

	@Schema(description = "已产生的执行尝试次数")
	private Integer attemptCount;

	@Schema(description = "最大尝试次数")
	private Integer maxAttempts;

	@Schema(description = "产出 Artifact ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long outputArtifactId;

	@Schema(description = "错误编码")
	private String errorCode;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "开始时间")
	private Instant startedAt;

	@Schema(description = "结束时间")
	private Instant finishedAt;

}
