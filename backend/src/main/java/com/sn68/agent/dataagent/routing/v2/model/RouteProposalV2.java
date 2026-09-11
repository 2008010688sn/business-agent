/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import java.util.List;

/**
 * 模型产出的路由提案(V2 协议,不可信输入)。
 *
 * <p>与 V1 RoutePlan 的关键差异:步骤数组允许乱序,依赖关系由 bindings(数据依赖)与
 * controlEdges(控制依赖)表达,由服务端 PlanCompiler 做 Kahn 拓扑排序与全量校验后
 * 编译为不可变 CompiledPlan;提案本身不携带任何数据库 ID、风险、审批、租户等服务端结论。
 */
public record RouteProposalV2(String schemaVersion, RouteProposalMode mode, List<RouteProposalStep> steps,
		List<RouteControlEdge> controlEdges, RouteProposalClarify clarify) {

	/** 协议固定版本号,解析时严格等值匹配。 */
	public static final String SCHEMA_VERSION = "route-proposal-v2";

	public RouteProposalV2 {
		if (schemaVersion == null || !SCHEMA_VERSION.equals(schemaVersion.trim())) {
			throw new IllegalArgumentException("Route proposal schemaVersion must be " + SCHEMA_VERSION);
		}
		schemaVersion = SCHEMA_VERSION;
		if (mode == null) {
			throw new IllegalArgumentException("Route proposal mode is required");
		}
		steps = steps == null ? List.of() : List.copyOf(steps);
		controlEdges = controlEdges == null ? List.of() : List.copyOf(controlEdges);
	}

}
