/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInvocation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 持久运行时外部副作用调用响应（对账清单用）。只回摘要与回执，不回原始请求参数与响应体。
 */
@Schema(description = "持久运行时外部副作用调用")
public record RuntimeInvocationResp(

		@Schema(description = "调用记录ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long id,

		@Schema(description = "所属运行ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long runId,

		@Schema(description = "幂等键")
		String idempotencyKey,

		@Schema(description = "被调用能力句柄")
		String capabilityHandle,

		@Schema(description = "调用类型：TOOL、SKILL、FLOW、AGENT、MCP、HTTP")
		String invocationType,

		@Schema(description = "调用状态")
		String state,

		@Schema(description = "错误编码")
		String errorCode,

		@Schema(description = "错误信息")
		String errorMessage,

		@Schema(description = "请求参数摘要（非原始参数）")
		String requestDigest,

		@Schema(description = "外部回执线索 JSON")
		String sideEffectReceipt,

		@Schema(description = "外部请求实际发出时间")
		Instant sentAt,

		@Schema(description = "创建时间")
		Instant createTime) {

	public static RuntimeInvocationResp from(AgentRuntimeInvocation invocation) {
		if (invocation == null) {
			return null;
		}
		return new RuntimeInvocationResp(invocation.getId(), invocation.getRunId(), invocation.getIdempotencyKey(),
				invocation.getCapabilityHandle(), invocation.getInvocationType(), invocation.getState(),
				invocation.getErrorCode(), invocation.getErrorMessage(), invocation.getRequestDigest(),
				invocation.getSideEffectReceipt(), invocation.getSentAt(), invocation.getCreateTime());
	}

}
