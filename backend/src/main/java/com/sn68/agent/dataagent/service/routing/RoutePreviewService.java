/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import com.sn68.agent.dataagent.dto.routing.RoutePreviewReq;
import com.sn68.agent.dataagent.dto.routing.RoutePreviewResp;

/**
 * 路由预览服务契约：在不真正执行的前提下模拟一次路由决策，供管理端调试路由画像。
 */
public interface RoutePreviewService {

	/**
	 * 对指定 Agent 以只读方式模拟一次路由决策，返回候选评分与决策快照，不产生任何副作用。
	 */
	RoutePreviewResp preview(Long agentId, RoutePreviewReq request);
}
