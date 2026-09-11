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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Append-only FLOW node audit event.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_agent_flow_event", autoResultMap = true)
public class DataAgentFlowEvent extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 租户ID。 */
	private String tenantId;

	/** 所属流程实例ID。 */
	private Long flowInstanceId;

	/** 触发本事件的运行请求ID。 */
	private String runtimeRequestId;

	/**
	 * Server-generated idempotency key for a received asynchronous FLOW continuation.
	 * It is an audit correlation key, not a client authorization token.
	 */
	private String eventKey;

	/** 节点ID。 */
	private String nodeId;

	/** 节点类型。 */
	private String nodeType;

	/** 事件类型（进入/完成/失败等）。 */
	private String eventType;

	/** 事件状态。 */
	private String status;

	/** 节点耗时（毫秒）。 */
	private Long durationMs;

	/** 节点输入数据（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String inputData;

	/** 节点输出数据（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String outputData;

	/** 错误码。 */
	private String errorCode;

	/** 错误信息。 */
	private String errorMessage;

}
