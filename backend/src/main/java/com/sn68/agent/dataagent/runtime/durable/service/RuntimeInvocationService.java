/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInvocation;
import java.util.List;

/**
 * 持久运行时外部副作用调用生命周期服务。
 *
 * <p>生命周期：DISPATCH_INTENT → INVOCATION_SENT → SUCCESS / FAILED / OUTCOME_UNKNOWN → RECONCILING
 * → SUCCESS / FAILED。硬约束：处于 OUTCOME_UNKNOWN 或 RECONCILING 的幂等键禁止自动重试，
 * {@link #open(InvocationOpen)} 对这类键抛出 CheckedException 提示先完成对账；
 * 本地中断/超时只能证明本地停止，不能证明外部副作用未发生，调用方必须落 OUTCOME_UNKNOWN 而不是 FAILED。</p>
 */
public interface RuntimeInvocationService {

	/**
	 * 幂等开启调用：(runId, idempotencyKey) 不存在则创建 DISPATCH_INTENT 记录；已存在则返回既有记录
	 * （SUCCESS 直接复用回执，FAILED 由调用方决定是否以新幂等键重试）。
	 *
	 * @throws com.sn68.agent.framework.commons.exception.CheckedException 既有记录处于 OUTCOME_UNKNOWN /
	 * RECONCILING 时抛出，禁止自动重试，需先对账
	 */
	AgentRuntimeInvocation open(InvocationOpen command);

	/**
	 * DISPATCH_INTENT → INVOCATION_SENT：外部请求实际发出前调用。
	 */
	boolean markSent(Long invocationId);

	/**
	 * INVOCATION_SENT → SUCCESS。
	 */
	boolean markSuccess(Long invocationId, String responseDigest, String sideEffectReceiptJson);

	/**
	 * INVOCATION_SENT → FAILED：仅在外部端明确返回失败（可证明副作用未生效）时使用。
	 */
	boolean markFailed(Long invocationId, String errorCode, String errorMessage);

	/**
	 * INVOCATION_SENT → OUTCOME_UNKNOWN：超时、连接中断、本地取消等无法证明外部结果的场景。
	 */
	boolean markOutcomeUnknown(Long invocationId, String errorCode, String errorMessage);

	/**
	 * OUTCOME_UNKNOWN → RECONCILING：进入对账流程。
	 */
	boolean beginReconcile(Long invocationId);

	/**
	 * RECONCILING → SUCCESS / FAILED：对账得出确定结论。
	 */
	boolean completeReconcile(Long invocationId, boolean success, String responseDigest, String sideEffectReceiptJson,
			String errorCode, String errorMessage);

	/**
	 * 管理端对账收敛：把 OUTCOME_UNKNOWN / RECONCILING 的调用按人工核对到的外部实际结果一次性
	 * 推到 SUCCESS 或 FAILED，解除该幂等键的「禁止重试」封锁。
	 *
	 * <p>不做自动对账：本地超时/中断只能证明本地停止，系统无从判断外部副作用是否已生效，
	 * 按时限自动重试等于放任重复写。因此收敛动作必须由运维在核对下游系统后显式发起。</p>
	 *
	 * @param tenantId 当前租户，必填，用于归属校验
	 * @param operator 操作人ID，落入对账回执，便于追溯
	 * @param invocationId 调用记录ID
	 * @param success 外部副作用实际是否已生效
	 * @param comment 对账说明（如核对到的外部单号 / 核对结论），失败时同时作为错误信息
	 */
	void reconcile(String tenantId, String operator, Long invocationId, boolean success, String comment);

	/**
	 * 待对账清单（OUTCOME_UNKNOWN / RECONCILING）。给运维一个可发现的入口，
	 * 否则被封锁的幂等键只能靠报错文案里的 ID 逐个捞。
	 */
	List<AgentRuntimeInvocation> listPendingReconcile(String tenantId, Integer limit);

	/**
	 * 开启调用命令。tenantId 允许为空（按 0 落库，仅限无租户上下文的系统级链路；能力网关侧对
	 * 携带 runId 的调用已强制真实租户）；stepId/attemptId 允许为空（run 级调用）。
	 */
	record InvocationOpen(String tenantId, Long runId, Long stepId, Long attemptId, String idempotencyKey,
			String capabilityHandle, String invocationType, String requestDigest) {
	}

}
