/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.AgentOrchestrationStep;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import java.util.List;

/**
 * 双写迁移期镜像服务：把遥测表（agent_orchestration_run / agent_orchestration_step）的
 * 生命周期节点镜像到权威运行时表（agent_runtime_run / agent_runtime_step / agent_runtime_event）。
 *
 * <p>定位与约束：</p>
 * <ul>
 * <li>遥测表保留不动、继续写入；权威表是运行状态的权威来源，二者以 source_run_id 关联。</li>
 * <li>所有方法幂等（唯一约束 + event_key 去重），重复调用不产生重复记录。</li>
 * <li>所有方法不向调用方抛异常：镜像失败以 error 日志暴露，不得中断既有编排链路。
 * 待编排引擎完全切换到权威表后，该"吞异常"策略随本服务一并移除。</li>
 * <li>W2 深度接线后，事件驱动调度管理的步骤（fence_token &gt; 0，取得过租约）生命周期由
 * {@link RuntimeDagScheduler} 独占写入，遥测步骤镜像对其跳过，避免双写竞争同一状态机。</li>
 * </ul>
 */
public interface RuntimeMirrorService {

	/**
	 * 编排入口：为遥测 Run 建立权威 Run 镜像（幂等，二次调用返回既有镜像）。
	 */
	void mirrorRunStarted(AgentRequest request, AgentOrchestrationRun legacyRun);

	/**
	 * 编排收尾：镜像 Run 终态/等待态。legacyStatus 为遥测层状态（running/success/failed/...）。
	 */
	void mirrorRunFinished(AgentOrchestrationRun legacyRun, String legacyStatus);

	/**
	 * 步骤创建：镜像权威 Step（RUNNING）并追加 STEP_STARTED 事件。调度器管理的步骤跳过。
	 */
	void mirrorStepStarted(AgentOrchestrationRun legacyRun, AgentOrchestrationStep legacyStep);

	/**
	 * 步骤收尾：镜像 Step 终态/等待态并追加对应事件。调度器管理的步骤跳过。
	 */
	void mirrorStepFinished(AgentOrchestrationStep legacyStep, String legacyStatus);

	/**
	 * W2 深度接线：为事件驱动调度准备权威步骤行（PENDING + depends_on），恢复链路带来的
	 * 既定终态（restoredState）直接收敛，使调度器的依赖评估与遥测结果一致。幂等：已存在的
	 * 步骤行不重复创建。返回权威 Run ID；权威 Run 镜像缺失或准备失败返回 null，调用方回落
	 * 整批 barrier 执行。
	 */
	Long prepareScheduledSteps(AgentOrchestrationRun legacyRun, List<ScheduledStepSpec> specs);

	/**
	 * 调度对账：读取权威 Run 下全部步骤当前状态，供执行引擎在完成事件缺失时兜底核对。
	 */
	List<AgentRuntimeStep> listScheduledSteps(Long runtimeRunId);

	/**
	 * 调度步骤准备参数。restoredState 非空表示该步骤结果已由恢复链路给定（不再执行），
	 * 步骤行直接落/收敛为该终态。
	 */
	record ScheduledStepSpec(String stepKey, String stepName, String capabilityHandle, List<String> dependsOn,
			int maxAttempts, RuntimeStepState restoredState, String restoredErrorCode, String restoredErrorMessage) {

		public ScheduledStepSpec {
			dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
		}

		public static ScheduledStepSpec pending(String stepKey, String stepName, String capabilityHandle,
				List<String> dependsOn, int maxAttempts) {
			return new ScheduledStepSpec(stepKey, stepName, capabilityHandle, dependsOn, maxAttempts, null, null, null);
		}

		public static ScheduledStepSpec restored(String stepKey, String stepName, String capabilityHandle,
				List<String> dependsOn, RuntimeStepState restoredState, String errorCode, String errorMessage) {
			return new ScheduledStepSpec(stepKey, stepName, capabilityHandle, dependsOn, 1, restoredState, errorCode,
					errorMessage);
		}

	}

}
