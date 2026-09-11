/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.routing;

import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前路由 Profile 全景视图（激活/工作/回滚三份 Profile 与生效配置）。
 */
@Schema(description = "当前路由Profile全景")
public record RouteProfileCurrentResp(
		@Schema(description = "当前激活的Profile") RouteProfileResp active,
		@Schema(description = "工作中（编辑中）的Profile") RouteProfileResp working,
		@Schema(description = "可回滚的历史Profile") RouteProfileResp rollback,
		@Schema(description = "当前生效的路由配置") RouteProfileConfigurationDTO configuration) {

	public static RouteProfileCurrentResp from(DataAgentRouteProfile active, DataAgentRouteProfile working,
			DataAgentRouteProfile rollback, RouteProfileConfigurationDTO configuration) {
		return new RouteProfileCurrentResp(RouteProfileResp.from(active), RouteProfileResp.from(working),
				RouteProfileResp.from(rollback), configuration);
	}

}
