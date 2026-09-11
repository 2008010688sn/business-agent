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
 * 持久运行时事务性外发消息实体（Outbox 模式：与状态变更同事务写入，由派发器异步投递）。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_runtime_outbox", autoResultMap = true)
@Schema(description = "持久运行时事务性外发消息")
public class AgentRuntimeOutbox extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "所属运行ID，系统级消息可为空")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long runId;

	@Schema(description = "来源事件ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long eventId;

	@Schema(description = "消息类型")
	private String messageType;

	@Schema(description = "目标通道：MQ、IM、WEBHOOK")
	private String targetChannel;

	@Schema(description = "消息负载 JSON")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String payload;

	@Schema(description = "派发状态：PENDING、DISPATCHED、FAILED、DEAD")
	private String state;

	@Schema(description = "已重试次数")
	private Integer retryCount;

	@Schema(description = "下次重试时间")
	private Instant nextRetryAt;

	@Schema(description = "派发成功时间")
	private Instant dispatchedAt;

	@Schema(description = "最近一次派发失败原因")
	private String lastError;

}
