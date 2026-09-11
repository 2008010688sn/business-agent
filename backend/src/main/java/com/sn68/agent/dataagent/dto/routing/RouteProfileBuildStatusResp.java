/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.routing;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 路由 Profile 物料构建进度视图。
 */
@Schema(description = "路由Profile构建状态")
public record RouteProfileBuildStatusResp(
		@Schema(description = "Profile ID") @JsonSerialize(using = ToStringSerializer.class) Long profileId,
		@Schema(description = "Profile状态") String status,
		@Schema(description = "构建状态") String buildStatus,
		@Schema(description = "物料总数") Integer total,
		@Schema(description = "已就绪数量") Integer ready,
		@Schema(description = "构建失败数量") Integer failed,
		@Schema(description = "最近一次错误编码") String errorCode,
		@Schema(description = "乐观锁版本号") Long revision) {

	public static RouteProfileBuildStatusResp from(DataAgentRouteProfile profile) {
		return new RouteProfileBuildStatusResp(profile.getId(), profile.getStatus(), profile.getBuildStatus(),
				profile.getBuildTotal(), profile.getBuildReady(), profile.getBuildFailed(), profile.getLastErrorCode(),
				profile.getRevision());
	}
}
