/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInvocation;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeInvocationState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeInvocationMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeInvocationService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 持久运行时外部副作用调用生命周期服务实现。
 *
 * <p>幂等创建（open）刻意不使用 @Transactional：依赖唯一约束冲突后在新语句中回查，
 * 单个 PostgreSQL 事务在首次冲突后即进入 aborted 状态无法继续。对账收敛（reconcile）是
 * 两步 CAS 的组合动作，需要事务保证不出现「已进对账中但未收敛」的中间态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeInvocationServiceImpl implements RuntimeInvocationService {

	/** 人工对账判定为失败时的错误编码，便于与外部端返回的失败区分。 */
	private static final String RECONCILE_ERROR_CODE = "RECONCILED_NOT_APPLIED";

	private static final int DEFAULT_RECONCILE_LIMIT = 50;

	private static final int MAX_RECONCILE_LIMIT = 200;

	private final AgentRuntimeInvocationMapper invocationMapper;

	private final ObjectMapper objectMapper;

	@Override
	public AgentRuntimeInvocation open(InvocationOpen command) {
		if (command == null || command.runId() == null || command.idempotencyKey() == null
				|| command.idempotencyKey().isBlank()) {
			throw CheckedException.badRequest("开启外部调用参数不完整");
		}
		AgentRuntimeInvocation existing = invocationMapper.findByRunIdAndIdempotencyKey(command.runId(),
				command.idempotencyKey());
		if (existing != null) {
			return requireRetryAllowed(existing);
		}
		AgentRuntimeInvocation invocation = AgentRuntimeInvocation.builder()
			// 调用记录按 (run_id, idempotency_key) 定位，tenant_id 用于对账入口的归属过滤；
			// 能力网关对带 runId 的调用已强制真实租户，落 0 的记录只会来自无租户上下文的系统级链路，
			// 其后果是不出现在管理端对账清单里（不可读，不可越权）。
			.tenantId(command.tenantId())
			.runId(command.runId())
			.stepId(command.stepId())
			.attemptId(command.attemptId())
			.idempotencyKey(command.idempotencyKey())
			.capabilityHandle(command.capabilityHandle())
			.invocationType(command.invocationType())
			.state(RuntimeInvocationState.DISPATCH_INTENT.getValue())
			.stateVersion(0L)
			.requestDigest(command.requestDigest())
			.createTime(Instant.now())
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
		try {
			invocationMapper.insert(invocation);
			return invocationMapper.selectById(invocation.getId());
		}
		catch (DuplicateKeyException conflict) {
			// 并发同幂等键创建：回查既有记录并按状态裁决，保证同一写操作不会重复下发
			AgentRuntimeInvocation winner = invocationMapper.findByRunIdAndIdempotencyKey(command.runId(),
					command.idempotencyKey());
			if (winner == null) {
				throw CheckedException.fail("外部调用幂等创建冲突后回查失败, idempotencyKey=" + command.idempotencyKey());
			}
			return requireRetryAllowed(winner);
		}
	}

	@Override
	public boolean markSent(Long invocationId) {
		return transition(invocationId, RuntimeInvocationState.DISPATCH_INTENT, RuntimeInvocationState.INVOCATION_SENT,
				null, null, null, null, Instant.now(), null, null);
	}

	@Override
	public boolean markSuccess(Long invocationId, String responseDigest, String sideEffectReceiptJson) {
		return transition(invocationId, RuntimeInvocationState.INVOCATION_SENT, RuntimeInvocationState.SUCCESS,
				responseDigest, sideEffectReceiptJson, null, null, null, Instant.now(), null);
	}

	@Override
	public boolean markFailed(Long invocationId, String errorCode, String errorMessage) {
		return transition(invocationId, RuntimeInvocationState.INVOCATION_SENT, RuntimeInvocationState.FAILED, null,
				null, errorCode, errorMessage, null, Instant.now(), null);
	}

	@Override
	public boolean markOutcomeUnknown(Long invocationId, String errorCode, String errorMessage) {
		return transition(invocationId, RuntimeInvocationState.INVOCATION_SENT, RuntimeInvocationState.OUTCOME_UNKNOWN,
				null, null, errorCode, errorMessage, null, null, null);
	}

	@Override
	public boolean beginReconcile(Long invocationId) {
		return transition(invocationId, RuntimeInvocationState.OUTCOME_UNKNOWN, RuntimeInvocationState.RECONCILING,
				null, null, null, null, null, null, null);
	}

	@Override
	public boolean completeReconcile(Long invocationId, boolean success, String responseDigest,
			String sideEffectReceiptJson, String errorCode, String errorMessage) {
		Instant now = Instant.now();
		return transition(invocationId, RuntimeInvocationState.RECONCILING,
				success ? RuntimeInvocationState.SUCCESS : RuntimeInvocationState.FAILED, responseDigest,
				sideEffectReceiptJson, errorCode, errorMessage, null, now, now);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void reconcile(String tenantId, String operator, Long invocationId, boolean success, String comment) {
		if (tenantId == null) {
			throw CheckedException.badRequest("租户上下文缺失");
		}
		if (invocationId == null) {
			throw CheckedException.badRequest("invocationId 不能为空");
		}
		AgentRuntimeInvocation invocation = invocationMapper.findByIdAndTenantId(invocationId, tenantId);
		if (invocation == null) {
			throw CheckedException.notFound("外部调用记录不存在或无权访问: " + invocationId);
		}
		RuntimeInvocationState state = RuntimeInvocationState.of(invocation.getState());
		if (state != RuntimeInvocationState.OUTCOME_UNKNOWN && state != RuntimeInvocationState.RECONCILING) {
			throw CheckedException.badRequest("仅结果未知/对账中的调用需要对账, 当前状态: " + invocation.getState()
					+ ", invocationId=" + invocationId);
		}
		if (state == RuntimeInvocationState.OUTCOME_UNKNOWN && !beginReconcile(invocationId)) {
			throw CheckedException.badRequest("对账已被并发处理, 请刷新后重试, invocationId=" + invocationId);
		}
		String receiptJson = reconcileReceiptJson(operator, success, comment);
		boolean converged = completeReconcile(invocationId, success, null, receiptJson,
				success ? null : RECONCILE_ERROR_CODE, success ? null : reconcileComment(comment));
		if (!converged) {
			throw CheckedException.fail("对账收敛失败, 状态已被并发变更, 请刷新后重试, invocationId=" + invocationId);
		}
		log.info("外部调用对账完成. invocationId={}, tenantId={}, success={}, operator={}", invocationId, tenantId,
				success, operator);
	}

	@Override
	public List<AgentRuntimeInvocation> listPendingReconcile(String tenantId, Integer limit) {
		if (tenantId == null) {
			throw CheckedException.badRequest("租户上下文缺失");
		}
		int safeLimit = limit == null || limit <= 0 ? DEFAULT_RECONCILE_LIMIT : Math.min(limit, MAX_RECONCILE_LIMIT);
		return invocationMapper.listPendingReconcile(tenantId, safeLimit);
	}

	/** 对账回执只落操作人与结论，不接收调用方传入的原始 JSON，避免脏数据写进 JSONB 列。 */
	private String reconcileReceiptJson(String operator, boolean success, String comment) {
		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("reconciledBy", operator == null ? "" : operator);
		receipt.put("reconciledOutcome", success ? RuntimeInvocationState.SUCCESS.getValue()
				: RuntimeInvocationState.FAILED.getValue());
		receipt.put("reconcileComment", reconcileComment(comment));
		try {
			return objectMapper.writeValueAsString(receipt);
		}
		catch (Exception ex) {
			throw CheckedException.fail("对账回执序列化失败：" + ex.getMessage());
		}
	}

	private String reconcileComment(String comment) {
		return comment == null || comment.isBlank() ? "人工对账" : comment.trim();
	}

	/**
	 * OUTCOME_UNKNOWN / RECONCILING 禁止自动重试的硬约束：重复 open 直接抛业务异常提示对账。
	 * 异常里带上 invocationId，运维据此调 {@code POST /runtime-invocations/{id}/reconcile} 解除封锁。
	 */
	private AgentRuntimeInvocation requireRetryAllowed(AgentRuntimeInvocation existing) {
		RuntimeInvocationState state = RuntimeInvocationState.of(existing.getState());
		if (state == RuntimeInvocationState.OUTCOME_UNKNOWN || state == RuntimeInvocationState.RECONCILING) {
			throw CheckedException.badRequest("外部调用结果未知，禁止自动重试，请先完成对账（运行记录「待对账」或 "
					+ "POST /runtime-invocations/" + existing.getId() + "/reconcile）, invocationId="
					+ existing.getId() + ", idempotencyKey=" + existing.getIdempotencyKey() + ", state="
					+ existing.getState());
		}
		return existing;
	}

	private boolean transition(Long invocationId, RuntimeInvocationState fromState, RuntimeInvocationState toState,
			String responseDigest, String sideEffectReceiptJson, String errorCode, String errorMessage, Instant sentAt,
			Instant completedAt, Instant reconciledAt) {
		if (invocationId == null) {
			throw CheckedException.badRequest("invocationId 不能为空");
		}
		boolean updated = invocationMapper.casTransition(invocationId, fromState.getValue(), toState.getValue(),
				responseDigest, sideEffectReceiptJson, errorCode, errorMessage, sentAt, completedAt,
				reconciledAt) > 0;
		if (!updated) {
			log.warn("外部调用状态迁移竞争失败. invocationId={}, from={}, to={}", invocationId, fromState, toState);
		}
		return updated;
	}

}
