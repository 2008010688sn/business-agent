/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.shadow;

/**
 * 一次影子对比的结构化记录：V1 决策事实 vs V2 编译结论，经 route.shadow 专用 logger 字段化输出。
 *
 * <p>不携带用户原文（只记录 query 长度与哈希前缀）；V2 拒绝而 V1 通过（divergence =
 * V2_REJECTED_V1_PASSED）是灰度期最有价值的差异信号，必须完整携带失败码与原因。
 */
public record ShadowRouteCompareResult(String tenantId, Long agentId, String requestId, Long sessionId,
		String v1Decision, String v1ReasonCode, String v1DegradeMode, boolean v1ModelInvoked, int v1SelectionCount,
		int v1PlanStepCount, String v1StepOrder, int candidateCount, String scenario, String skippedReason,
		String v2Mode, Integer v2StepCount, String v2ExecutionOrder, String planHash, String v2RiskLevel,
		Boolean v2ApprovalRequired, String compileFailureCode, String compileFailureMessage, String divergence,
		String persistence, Long persistedPlanId, int queryLength, String queryHash, long elapsedMs) {

	/** V2 编译成功。 */
	public static final String SCENARIO_COMPILED = "COMPILED";

	/** V2 编译拒绝（PlanCompiler 失败码见 compileFailureCode）。 */
	public static final String SCENARIO_COMPILE_REJECTED = "COMPILE_REJECTED";

	/** 未进入编译（无计划决策 / 候选缺失 / 预算耗尽等，原因见 skippedReason）。 */
	public static final String SCENARIO_SKIPPED = "SKIPPED";

	public static final String DIVERGENCE_NONE = "NONE";

	public static final String DIVERGENCE_V2_REJECTED_V1_PASSED = "V2_REJECTED_V1_PASSED";

	public static final String DIVERGENCE_NOT_COMPARED = "NOT_COMPARED";

	public static final String PERSISTENCE_PERSISTED = "PERSISTED";

	public static final String PERSISTENCE_SKIPPED_NO_RUN = "SKIPPED_NO_RUN";

	public static final String PERSISTENCE_SKIPPED_BUDGET_EXHAUSTED = "SKIPPED_BUDGET_EXHAUSTED";

	public static final String PERSISTENCE_SKIPPED_NOT_COMPILED = "SKIPPED_NOT_COMPILED";

	public static final String PERSISTENCE_FAILED = "FAILED";

}
