/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建持久运行时 Run 请求。(tenantId, ownerType, ownerId, clientRequestId) 为幂等键（租户取自授权上下文，
 * 不由请求体给定），重复请求返回既有 Run；幂等域限本租户内，clientRequestId 不会被其他租户占用。
 * <p><b>PR-1:</b> 移除 workspaceId 隔离语义，改用 owner 维度与数字员工归属。</p>
 */
@Schema(description = "创建持久运行时 Run 请求")
public record RuntimeRunCreateReq(

		@Schema(description = "调用方请求 ID（幂等键，重复请求不会重复创建）")
		@NotBlank(message = "clientRequestId 不能为空")
		@Size(max = 128, message = "clientRequestId 长度不能超过 128")
		String clientRequestId,

		@Schema(description = "运行主体类型：DIGITAL_EMPLOYEE / CALLER / PLATFORM")
		String ownerType,

		@Schema(description = "运行主体 ID (ownerType 为 DIGITAL_EMPLOYEE 时为 digitalEmployeeId，CALLER 时为用户 id)")
		Long ownerId,

		@Schema(description = "所属数字员工 ID(可空，调用链路直接归属该员工时填充)")
		Long digitalEmployeeId,

		@Schema(description = "DataAgent 发布版本 ID")
		Long releaseId,

		@Schema(description = "运行的 DataAgent ID")
		Long agentId,

		@Schema(description = "会话ID")
		@Size(max = 128, message = "threadId 长度不能超过 128")
		String threadId,

		@Schema(description = "用户问题或任务描述")
		String query,

		@Schema(description = "运行模式：CHAT、DIRECT、AGENT_LOOP、ORCHESTRATION、FLOW")
		@Size(max = 32, message = "runMode 长度不能超过 32")
		String runMode,

		@Schema(description = "运行截止秒数，空表示不限制")
		@Positive(message = "deadlineSeconds 必须为正数")
		Long deadlineSeconds,

		@Schema(description = "触发来源：API、CHAT、IM、SCHEDULE、EVENT；空则按 API")
		@Size(max = 32, message = "triggerSource 长度不能超过 32")
		String triggerSource) {
}
