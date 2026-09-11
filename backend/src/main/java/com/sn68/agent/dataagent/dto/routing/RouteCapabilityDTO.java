/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.routing;

import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 脱敏后的路由能力探测快照，刻意不包含配置指纹、凭证、提示词、推理过程与上游响应原文。
 */
@Schema(description = "路由能力探测快照")
public record RouteCapabilityDTO(
		@Schema(description = "探测状态") String state,
		@Schema(description = "探测协议") String protocol,
		@Schema(description = "探测耗时（毫秒）") Long latencyMs,
		@Schema(description = "最近探测时间") Instant checkedAt,
		@Schema(description = "失败编码") String failureCode,
		@Schema(description = "运行时是否就绪") Boolean runtimeReady) {

	public static RouteCapabilityDTO routeModel(DataAgentRouteProfile profile) {
		return new RouteCapabilityDTO(defaultState(profile.getRouteModelProbeState()),
				defaultProtocol(profile.getRouteModelProtocol()),
				profile.getRouteModelLatencyMs(), profile.getRouteModelCheckedAt(),
				profile.getRouteModelFailureCode(), Boolean.TRUE.equals(profile.getRouteModelRuntimeReady()));
	}

	public static RouteCapabilityDTO embedding(DataAgentRouteProfile profile) {
		return new RouteCapabilityDTO(defaultState(profile.getEmbeddingProbeState()), "NONE",
				profile.getEmbeddingLatencyMs(),
				profile.getEmbeddingCheckedAt(), profile.getEmbeddingFailureCode(), null);
	}

	private static String defaultState(String value) {
		return value == null ? "NOT_PROBED" : value;
	}

	private static String defaultProtocol(String value) {
		return value == null ? "NONE" : value;
	}
}
