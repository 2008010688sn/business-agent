/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlan;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlanStep;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import com.sn68.agent.dataagent.runtime.durable.service.CompiledPlanPersistenceService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.util.StringUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 编译执行计划持久化桥实现。
 *
 * <p>刻意不使用 @Transactional：幂等创建依赖 (run_id, plan_version) 唯一约束冲突后在新语句中回查，
 * 与 RuntimeRunServiceImpl 的幂等创建模式保持一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompiledPlanPersistenceServiceImpl implements CompiledPlanPersistenceService {

	private static final String STATUS_ACTIVE = "ACTIVE";

	private static final String STATUS_SUPERSEDED = "SUPERSEDED";

	/** 影子对比计划（灰度期 V1 决策经 V2 编译的对比产物），不参与执行、不被读取端裁决。 */
	private static final String STATUS_SHADOW = "SHADOW";

	/** 正式保存与影子保存竞争 plan_version 的重试上限：每轮以新版本重插，正常并发一两轮内收敛。 */
	private static final int SAVE_CONFLICT_RETRY = 3;

	private final AgentRuntimePlanMapper planMapper;

	private final ObjectMapper objectMapper;

	@Override
	public AgentRuntimePlan save(Long runId, CompiledPlan plan) {
		if (runId == null || plan == null) {
			throw CheckedException.badRequest("持久化编译计划需要 runId 与 CompiledPlan");
		}
		// 有界重试覆盖 S1 遗留竞态：同 planHash 下 save 与 saveShadow 竞争 plan_version 时，
		// 冲突回查必须过滤 SHADOW 行（影子计划永不作为正式计划返回），输给影子行则换新版本重插
		for (int attempt = 0; attempt < SAVE_CONFLICT_RETRY; attempt++) {
			AgentRuntimePlan existing = planMapper.findNonShadowByRunIdAndPlanHash(runId, plan.planHash());
			if (existing != null) {
				return existing;
			}
			AgentRuntimePlan entity = buildPlanEntity(runId, plan, STATUS_ACTIVE);
			try {
				planMapper.insert(entity);
			}
			catch (DuplicateKeyException conflict) {
				// 并发保存竞争 plan_version：唯一约束保证只有一个成功。赢家可能是同 hash 的正式保存
				// （幂等返回），也可能是同 hash 的影子保存（不可复用，进入下一轮以新版本重插）
				AgentRuntimePlan winner = planMapper.findNonShadowByRunIdAndPlanHash(runId, plan.planHash());
				if (winner != null) {
					return winner;
				}
				continue;
			}
			supersedeOldActivePlans(runId, entity.getId());
			log.info("编译计划已持久化. runId={}, planId={}, planVersion={}, planHash={}, stepCount={}", runId,
					entity.getId(), entity.getPlanVersion(), entity.getPlanHash(), entity.getStepCount());
			return planMapper.selectById(entity.getId());
		}
		throw CheckedException.fail("编译计划并发保存冲突重试耗尽, runId=" + runId + ", planHash=" + plan.planHash());
	}

	@Override
	public AgentRuntimePlan saveShadow(Long runId, CompiledPlan plan) {
		if (runId == null || plan == null) {
			throw CheckedException.badRequest("持久化影子编译计划需要 runId 与 CompiledPlan");
		}
		// 任意状态的同 hash 记录都视为幂等命中：正式计划已存在同 hash 时无需再落影子行，
		// 这本身就是"native 会产出同一计划"的最强一致信号（对比日志仍完整记录 planHash）
		AgentRuntimePlan existing = planMapper.findByRunIdAndPlanHash(runId, plan.planHash());
		if (existing != null) {
			return existing;
		}
		AgentRuntimePlan entity = buildPlanEntity(runId, plan, STATUS_SHADOW);
		try {
			planMapper.insert(entity);
		}
		catch (DuplicateKeyException conflict) {
			// 与正式保存同款幂等恢复：plan_version 竞争失败后按 planHash 回查既有记录
			AgentRuntimePlan winner = planMapper.findByRunIdAndPlanHash(runId, plan.planHash());
			if (winner == null) {
				throw CheckedException.fail(
						"影子编译计划并发保存冲突后回查失败, runId=" + runId + ", planHash=" + plan.planHash());
			}
			return winner;
		}
		// 影子计划不替换 ACTIVE、也不被后续版本替换，永久保留为灰度对比数据
		log.info("影子编译计划已持久化. runId={}, planId={}, planVersion={}, planHash={}, stepCount={}", runId,
				entity.getId(), entity.getPlanVersion(), entity.getPlanHash(), entity.getStepCount());
		return planMapper.selectById(entity.getId());
	}

	private AgentRuntimePlan buildPlanEntity(Long runId, CompiledPlan plan, String status) {
		Instant now = Instant.now();
		return AgentRuntimePlan.builder()
			.tenantId(parseTenantId(plan.tenantId()))
			.runId(runId)
			.planVersion(nextPlanVersion(runId))
			.planHash(plan.planHash())
			.status(status)
			.compiledPlan(plan.canonicalJson())
			.dagEdges(dagEdgesJson(plan))
			.stepCount(plan.steps().size())
			.policySnapshot(plan.policySnapshot())
			.absoluteDeadlineAt(plan.absoluteDeadline())
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
	}

	/** 新计划先落库再替换旧 ACTIVE，读取端按 plan_version 降序取 ACTIVE，短暂双 ACTIVE 不影响裁决。 */
	private void supersedeOldActivePlans(Long runId, Long keepPlanId) {
		planMapper.update(null, Wraps.<AgentRuntimePlan>lbU()
			.eq(AgentRuntimePlan::getRunId, runId)
			.eq(AgentRuntimePlan::getStatus, STATUS_ACTIVE)
			.ne(AgentRuntimePlan::getId, keepPlanId)
			.set(AgentRuntimePlan::getStatus, STATUS_SUPERSEDED)
			.set(AgentRuntimePlan::getLastModifyTime, Instant.now()));
	}

	private Integer nextPlanVersion(Long runId) {
		AgentRuntimePlan latest = planMapper.findLatestByRunId(runId);
		return latest == null || latest.getPlanVersion() == null ? 1 : latest.getPlanVersion() + 1;
	}

	private String parseTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			throw CheckedException.badRequest("编译计划缺少租户, 拒绝持久化, tenantId=" + tenantId);
		}
		return tenantId.trim();
	}

	/** DAG 依赖边清单，元素为 [fromStepKey, toStepKey]，与表注释约定一致。 */
	private String dagEdgesJson(CompiledPlan plan) {
		List<List<String>> edges = new ArrayList<>();
		for (CompiledPlanStep step : plan.steps()) {
			for (String from : step.dependsOn()) {
				edges.add(List.of(from, step.stepKey()));
			}
		}
		try {
			return objectMapper.writeValueAsString(edges);
		}
		catch (Exception ex) {
			throw CheckedException.fail("编译计划 DAG 边序列化失败: " + ex.getMessage());
		}
	}

}
