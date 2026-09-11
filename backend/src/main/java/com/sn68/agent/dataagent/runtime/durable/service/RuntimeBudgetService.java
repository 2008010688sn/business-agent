/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

/**
 * 持久运行时预算服务：run 维度的能力调用记账（agent_runtime_budget_ledger）与超限拒绝。
 *
 * <p>预算上限取自该 run 生效计划（agent_runtime_plan.policy_snapshot 的
 * {@code maxCapabilityCalls} 字段）；无计划或未声明时用默认常量。检查失败关闭：
 * 超限或检查异常一律拒绝，不降级放行。</p>
 */
public interface RuntimeBudgetService {

	/**
	 * per-run 能力调用预算检查：已记账 TOOL_CALL 次数达到上限时抛 CheckedException 拒绝。
	 * runId 为空表示无 run 维度（如管理端直调），不做 run 级预算检查。
	 */
	void enforceCapabilityCallBudget(Long runId);

	/**
	 * 能力调用完成后记账：TOOL_CALL 计数 1 次 + DURATION_MS 记耗时。副作用已发生，
	 * 记账失败记录 error 日志但不使调用结果失败（不吞：日志可见、可对账补录）。
	 */
	void recordCapabilityCall(String tenantId, Long runId, String stepKey, Long refInvocationId, long durationMs,
			String capabilityCode);

}
