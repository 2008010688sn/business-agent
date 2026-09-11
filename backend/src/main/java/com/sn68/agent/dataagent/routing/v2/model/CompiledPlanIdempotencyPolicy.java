/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

/**
 * 编译计划的幂等策略。幂等键由服务端运行时按策略生成,提案不可指定。
 */
public enum CompiledPlanIdempotencyPolicy {

	/** 幂等键 = planHash + runId + stepKey,同一计划同一步骤重复触发不产生重复副作用。 */
	PLAN_HASH_STEP_KEY

}
