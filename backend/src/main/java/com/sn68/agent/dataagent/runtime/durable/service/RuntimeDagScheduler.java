/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

/**
 * 持久运行时事件驱动 DAG 调度器。
 *
 * <p>与整批 barrier（等待同批全部步骤结束再放行下一批）不同：任何一个步骤进入终态后，
 * 调度器立即重新计算其下游步骤的就绪性，依赖全部满足的下游步骤马上被释放执行，
 * 互不依赖的分支全程并行。步骤依赖关系来自 agent_runtime_step.depends_on（JSON 数组，
 * 编译计划时由 agent_runtime_plan 写入）。</p>
 *
 * <p>调度使用既有 orchestrationExecutor 线程池执行步骤，不新建线程池；
 * 步骤认领依赖租约 + fence（多节点同抢只有一个成功），执行结果经 CAS 收敛，
 * 全部步骤终态后自动收敛 Run 终态。</p>
 */
public interface RuntimeDagScheduler {

	/**
	 * 启动运行：PENDING → RUNNING（CAS），随后释放所有无依赖步骤。
	 * 幂等：运行已在 RUNNING 时仅重新计算就绪步骤（可用于节点接管后续跑）。
	 */
	void start(Long runId, RuntimeStepExecutor executor);

	/**
	 * 步骤终态回调：立即计算并释放该步骤的就绪下游（事件驱动，不等整批），
	 * 上游失败/取消/跳过的下游会被级联 SKIPPED；全部步骤终态后收敛 Run 终态。
	 */
	void onStepTerminal(Long runId, String stepKey, RuntimeStepExecutor executor);

}
