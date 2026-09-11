/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import com.sn68.agent.dataagent.routing.v2.model.CompiledPlan;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;

/**
 * 编译执行计划持久化桥：把路由 V2 编译器（纯内存 PlanCompiler）产出的 {@link CompiledPlan}
 * 落 agent_runtime_plan，计划持久化后才允许执行（方案第四章第 10 步）。
 *
 * <p>依赖方向为 runtime.durable → routing.v2 模型的单向依赖；PlanCompiler 本体保持纯内存，
 * 不感知持久化。</p>
 */
public interface CompiledPlanPersistenceService {

	/**
	 * 持久化编译计划：canonical JSON 全量落 compiled_plan，租户/工作区/Release 等编译上下文
	 * 均包含在 canonical JSON 内，租户与截止时间冗余为独立列供查询。
	 *
	 * <p>planHash 幂等：同一 run 下相同 planHash 已存在时返回既有记录，不重复落库；
	 * 新 planHash 追加 plan_version（同一 run 内唯一）并把旧 ACTIVE 计划置 SUPERSEDED。</p>
	 *
	 * <p>生产调用方是编排 {@code DurableCompiledPlanActivator}（durable run 开跑前）。
	 * NATIVE 执行链路本轮不落地，本方法只负责 ACTIVE 落库，不驱动步骤执行。</p>
	 */
	AgentRuntimePlan save(Long runId, CompiledPlan plan);

	/**
	 * 持久化影子编译计划（灰度期 V1 决策经 V2 编译的对比产物）：status 固定 SHADOW，
	 * 不参与执行、不替换该 run 下的 ACTIVE 计划、永不被读取端裁决。
	 *
	 * <p>(runId, planHash) 幂等：同 hash 记录（含 ACTIVE/SUPERSEDED）已存在时直接返回既有记录，
	 * 不重复落影子行；plan_version 与正式计划共用同一 run 内递增序列。</p>
	 */
	AgentRuntimePlan saveShadow(Long runId, CompiledPlan plan);

}
