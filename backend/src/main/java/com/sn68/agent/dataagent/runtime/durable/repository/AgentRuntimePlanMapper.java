/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 持久运行时执行计划 Mapper。查询发生在已校验租户归属的 run 语境内。
 */
@Repository
public interface AgentRuntimePlanMapper extends SuperMapper<AgentRuntimePlan> {

	default AgentRuntimePlan findActiveByRunId(Long runId) {
		return selectOne(Wraps.<AgentRuntimePlan>lbQ()
			.eq(AgentRuntimePlan::getRunId, runId)
			.eq(AgentRuntimePlan::getStatus, "ACTIVE")
			.orderByDesc(AgentRuntimePlan::getPlanVersion)
			.last("LIMIT 1"));
	}

	default List<AgentRuntimePlan> listByRunId(Long runId) {
		return selectList(Wraps.<AgentRuntimePlan>lbQ()
			.eq(AgentRuntimePlan::getRunId, runId)
			.orderByAsc(AgentRuntimePlan::getPlanVersion));
	}

	/**
	 * 按 (runId, planHash) 查询，供编译计划持久化幂等判定：同 hash 重复保存返回既有记录。
	 */
	default AgentRuntimePlan findByRunIdAndPlanHash(Long runId, String planHash) {
		return selectOne(Wraps.<AgentRuntimePlan>lbQ()
			.eq(AgentRuntimePlan::getRunId, runId)
			.eq(AgentRuntimePlan::getPlanHash, planHash)
			.orderByDesc(AgentRuntimePlan::getPlanVersion)
			.last("LIMIT 1"));
	}

	/**
	 * 按 (runId, planHash) 查询非影子记录，供正式保存的幂等判定与并发冲突回查：
	 * 影子行（status=SHADOW）永不参与执行，不得被当作正式计划返回。
	 */
	default AgentRuntimePlan findNonShadowByRunIdAndPlanHash(Long runId, String planHash) {
		return selectOne(Wraps.<AgentRuntimePlan>lbQ()
			.eq(AgentRuntimePlan::getRunId, runId)
			.eq(AgentRuntimePlan::getPlanHash, planHash)
			.ne(AgentRuntimePlan::getStatus, "SHADOW")
			.orderByDesc(AgentRuntimePlan::getPlanVersion)
			.last("LIMIT 1"));
	}

	/**
	 * 查询 run 下最新版本计划（不限状态），供追加 plan_version 使用。
	 */
	default AgentRuntimePlan findLatestByRunId(Long runId) {
		return selectOne(Wraps.<AgentRuntimePlan>lbQ()
			.eq(AgentRuntimePlan::getRunId, runId)
			.orderByDesc(AgentRuntimePlan::getPlanVersion)
			.last("LIMIT 1"));
	}

}
