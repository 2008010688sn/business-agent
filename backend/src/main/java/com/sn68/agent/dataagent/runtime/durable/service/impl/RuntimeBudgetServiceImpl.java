/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeBudgetLedger;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeBudgetType;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeBudgetLedgerMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeBudgetService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 持久运行时预算服务实现。
 *
 * <p>「查计数再放行」非严格原子：并发调用可能短暂越限 1~2 次，预算是成本护栏而非资金账务，
 * 不为此加行锁；记账为两行流水（TOOL_CALL 计数 + DURATION_MS 耗时），budget_type 区分口径。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeBudgetServiceImpl implements RuntimeBudgetService {

	/** 计划未声明预算时的 per-run 能力调用次数默认上限。 */
	static final int DEFAULT_MAX_CAPABILITY_CALLS_PER_RUN = 100;

	/**
	 * 计划策略快照（agent_runtime_plan.policy_snapshot）中声明 per-run 能力调用上限的字段名，
	 * 值为正整数；未声明或非法时回退 {@link #DEFAULT_MAX_CAPABILITY_CALLS_PER_RUN}。
	 */
	static final String POLICY_MAX_CAPABILITY_CALLS = "maxCapabilityCalls";

	private final AgentRuntimeBudgetLedgerMapper budgetLedgerMapper;

	private final AgentRuntimePlanMapper planMapper;

	private final ObjectMapper objectMapper;

	@Override
	public void enforceCapabilityCallBudget(Long runId) {
		if (runId == null) {
			return;
		}
		BigDecimal used = budgetLedgerMapper.sumAmount(runId, RuntimeBudgetType.TOOL_CALL.getValue());
		int limit = resolveCapabilityCallLimit(runId);
		if (used != null && used.compareTo(BigDecimal.valueOf(limit)) >= 0) {
			// 用 fail 带上 used/limit，让调用方一眼看出是被预算拦下而不是被权限拦下。
			throw CheckedException.fail("限流与预算检查拒绝：运行的能力调用次数已达预算上限, runId=" + runId
					+ ", used=" + used.stripTrailingZeros().toPlainString() + ", limit=" + limit);
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void recordCapabilityCall(String tenantId, Long runId, String stepKey, Long refInvocationId, long durationMs,
			String capabilityCode) {
		if (runId == null) {
			return;
		}
		try {
			Instant now = Instant.now();
			budgetLedgerMapper.insert(ledger(tenantId, runId, stepKey, refInvocationId, capabilityCode, now,
					RuntimeBudgetType.TOOL_CALL, BigDecimal.ONE));
			budgetLedgerMapper.insert(ledger(tenantId, runId, stepKey, refInvocationId, capabilityCode, now,
					RuntimeBudgetType.DURATION_MS, BigDecimal.valueOf(Math.max(0L, durationMs))));
		}
		catch (RuntimeException ex) {
			// 能力副作用已发生，记账失败不可再让调用报错回滚外部副作用；error 日志留对账线索。
			log.error("预算流水记账失败. runId={}, capabilityCode={}, durationMs={}", runId, capabilityCode,
					durationMs, ex);
		}
	}

	/** 预算上限来源：生效计划 policy_snapshot.maxCapabilityCalls，取不到用默认常量（不放行、只回退口径）。 */
	private int resolveCapabilityCallLimit(Long runId) {
		AgentRuntimePlan plan = planMapper.findActiveByRunId(runId);
		if (plan == null || !StringUtils.hasText(plan.getPolicySnapshot())) {
			return DEFAULT_MAX_CAPABILITY_CALLS_PER_RUN;
		}
		try {
			JsonNode node = objectMapper.readTree(plan.getPolicySnapshot()).path(POLICY_MAX_CAPABILITY_CALLS);
			if (node.isInt() || node.isLong()) {
				int declared = node.asInt();
				if (declared > 0) {
					return declared;
				}
			}
		}
		catch (Exception ex) {
			log.warn("解析计划策略快照预算失败, 回退默认上限. runId={}, planId={}", runId, plan.getId());
		}
		return DEFAULT_MAX_CAPABILITY_CALLS_PER_RUN;
	}

	private AgentRuntimeBudgetLedger ledger(String tenantId, Long runId, String stepKey, Long refInvocationId,
			String capabilityCode, Instant now, RuntimeBudgetType type, BigDecimal amount) {
		return AgentRuntimeBudgetLedger.builder()
			// 流水按 run_id 汇总与限额，tenant_id 只是冗余元数据；能力网关侧对带 runId 的调用
			// 已强制解析出真实租户，这里的 0 在网关链路不可达。
			.tenantId(tenantId)
			.runId(runId)
			.stepKey(stepKey)
			.budgetType(type.getValue())
			.amount(amount)
			.refInvocationId(refInvocationId)
			.remark(capabilityCode)
			.occurredAt(now)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
	}

}
