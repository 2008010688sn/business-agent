/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.routing;

import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/**
 * 混合路由预览请求，用于按指定 Profile 试算一次路由决策。
 */
@Schema(description = "混合路由预览请求")
public record RoutePreviewReq(
		@Schema(description = "用户问题文本") String query,
		@Schema(description = "路由Profile ID（缺省用当前激活Profile）") Long profileId,
		@Schema(description = "参与路由的目标类型集合") Set<RouteTargetType> targetTypes) {
}
