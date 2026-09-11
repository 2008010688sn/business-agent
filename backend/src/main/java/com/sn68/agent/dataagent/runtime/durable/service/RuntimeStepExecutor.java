/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import java.util.List;

/**
 * 持久运行时步骤执行体。
 *
 * <p>由调度器在步骤取得租约并 CAS 进入 RUNNING 后回调；实现方（编排协作者执行、CapabilityGateway
 * 调用等）只关心单步业务执行，租约、fence、状态机、事件与下游释放由 {@link RuntimeDagScheduler} 负责。
 * 实现方应在长任务中周期性检查取消（run.cancellationEpoch / 中断标记）并尽快返回。</p>
 */
@FunctionalInterface
public interface RuntimeStepExecutor {

	/**
	 * 执行单个步骤。抛出异常视为步骤失败；本地中断/超时且外部副作用结果未知时，
	 * 实现方必须先通过 {@link RuntimeInvocationService#markOutcomeUnknown} 落结果未知，再返回失败。
	 */
	StepOutcome execute(StepExecution execution) throws Exception;

	/**
	 * 依赖已满足的步骤释放（PENDING→READY）前询问执行体是否放行。返回 false 时步骤保持
	 * PENDING，下一次终态事件重算就绪性时再次询问。供执行体表达调度门控（如编排的
	 * 交互型协作者独占执行、挂起/快速失败后停发新步骤），默认放行。
	 */
	default boolean canRelease(AgentRuntimeRun run, AgentRuntimeStep step) {
		return true;
	}

	/**
	 * 步骤执行上下文。fenceToken 为本次认领取得的栅栏令牌，实现方进行外部调用时应透传作为隔离依据。
	 */
	record StepExecution(AgentRuntimeRun run, AgentRuntimeStep step, int attemptNo, long fenceToken) {
	}

	/**
	 * 步骤成功时除主产物外再落的附加产物（同一 stepKey，不同 schemaVersion）。
	 */
	record ExtraArtifact(String schemaVersion, String artifactJson) {
	}

	/**
	 * 步骤执行结果。成功时可携带结构化产物 JSON（落 agent_runtime_artifact 并被下游读取）；
	 * waiting=true 表示步骤挂起等待人工交互，调度器将其置为 WAITING（非终态）：不释放下游、
	 * 不收敛 Run，恢复链路收敛该步骤终态后调度继续。
	 */
	record StepOutcome(boolean success, String artifactJson, String artifactSchemaVersion, String errorCode,
			String errorMessage, boolean waiting, List<ExtraArtifact> extraArtifacts) {

		public StepOutcome {
			extraArtifacts = extraArtifacts == null ? List.of() : List.copyOf(extraArtifacts);
		}

		public static StepOutcome success(String artifactJson, String artifactSchemaVersion) {
			return success(artifactJson, artifactSchemaVersion, List.of());
		}

		public static StepOutcome success(String artifactJson, String artifactSchemaVersion,
				List<ExtraArtifact> extraArtifacts) {
			return new StepOutcome(true, artifactJson, artifactSchemaVersion, null, null, false, extraArtifacts);
		}

		public static StepOutcome failure(String errorCode, String errorMessage) {
			return new StepOutcome(false, null, null, errorCode, errorMessage, false, List.of());
		}

		public static StepOutcome waitingInteraction() {
			return new StepOutcome(false, null, null, null, null, true, List.of());
		}

	}

}
