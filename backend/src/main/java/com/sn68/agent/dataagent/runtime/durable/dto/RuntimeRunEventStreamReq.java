/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 持久运行时事件 SSE 流请求。断线重连时携最后收到的 seq 作为 afterSeq，即可无遗漏、无乱序、无重复续订。
 */
@Schema(description = "持久运行时事件 SSE 流请求")
public record RuntimeRunEventStreamReq(

		@Schema(description = "运行ID")
		@NotNull(message = "runId 不能为空")
		Long runId,

		@Schema(description = "回放游标：返回 seq 大于该值的事件，空或 0 表示从头回放")
		Long afterSeq) {
}
