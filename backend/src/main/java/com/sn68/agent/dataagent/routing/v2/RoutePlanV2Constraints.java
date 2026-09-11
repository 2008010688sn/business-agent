/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2;

/**
 * V2 编排协议的默认约束(方案第三章执行原则第 7 条),集中定义避免魔法值散落。
 */
public final class RoutePlanV2Constraints {

	/** 单个计划的最大步骤数。 */
	public static final int MAX_STEPS = 8;

	/** 单个步骤的最大下游扇出数。 */
	public static final int MAX_FANOUT = 4;

	/** 嵌套编排最大深度:编排计划本身占 1 层,步骤能力自身再展开编排即超限。 */
	public static final int MAX_NESTED_ORCHESTRATION_DEPTH = 1;

	private RoutePlanV2Constraints() {
	}

}
