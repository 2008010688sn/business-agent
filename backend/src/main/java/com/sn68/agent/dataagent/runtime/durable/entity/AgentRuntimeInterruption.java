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
 * 持久运行时中断请求实体。内存注册表的取消标记写穿到本表，使取消跨节点、跨重启可见。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_runtime_interruption")
@Schema(description = "持久运行时中断请求记录")
public class AgentRuntimeInterruption extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "所属运行ID，按请求ID取消且未落 run 时可为空")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long runId;

	@Schema(description = "会话ID")
	private String threadId;

	@Schema(description = "运行时请求ID（全局唯一 UUID）")
	private String runtimeRequestId;

	@Schema(description = "中断类型：CANCEL、TIMEOUT、MANUAL_TAKEOVER")
	private String interruptionType;

	@Schema(description = "中断状态：REQUESTED、APPLIED")
	private String state;

	@Schema(description = "中断原因")
	private String reason;

	@Schema(description = "发起人ID")
	private String requestedBy;

	@Schema(description = "本次中断对应的取消纪元")
	private Long cancellationEpoch;

	@Schema(description = "请求时间")
	private Instant requestedAt;

	@Schema(description = "生效时间")
	private Instant appliedAt;

}
