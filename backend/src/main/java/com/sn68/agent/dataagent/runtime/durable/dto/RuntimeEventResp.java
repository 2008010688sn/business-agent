/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 持久运行时事件响应。seq 单调递增，客户端以最后收到的 seq 作为断线重放游标。
 */
@Schema(description = "持久运行时事件响应")
public record RuntimeEventResp(

		@Schema(description = "run 内单调递增序号")
		Long seq,

		@Schema(description = "事件幂等键")
		String eventKey,

		@Schema(description = "事件类型")
		String eventType,

		@Schema(description = "关联步骤键")
		String stepKey,

		@Schema(description = "事件负载（JSON 原文）")
		@JsonRawValue
		String payload,

		@Schema(description = "事件发生时间")
		Instant occurredAt) {

	public static RuntimeEventResp from(AgentRuntimeEvent event) {
		if (event == null) {
			return null;
		}
		String payload = event.getPayload();
		return new RuntimeEventResp(event.getSeq(), event.getEventKey(), event.getEventType(), event.getStepKey(),
				payload == null || payload.isBlank() ? "{}" : payload, event.getOccurredAt());
	}

}
