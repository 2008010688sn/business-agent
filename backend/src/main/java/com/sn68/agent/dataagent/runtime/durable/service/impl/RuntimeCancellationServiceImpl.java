/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInterruption;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeInterruptionType;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeInterruptionMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeCancellationService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.RunStateChange;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 持久运行时取消服务实现。
 *
 * <p>刻意不使用 @Transactional：各写入单条原子且幂等（纪元递增受终态谓词保护、中断记录允许多条、
 * 状态推进走 CAS、事件按 event_key 幂等），部分失败可由重复取消请求自愈。</p>
 *
 * <p>空闲运行（PENDING / 等待审批 / 等待输入）在本类直接落 CANCELLED 终态，终态 Outbox 也必须
 * 在此旁路写入并以 CAS 成功为闸门：重复取消时 CAS 已被终态谓词挡下，不会写出第二条消息。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeCancellationServiceImpl implements RuntimeCancellationService {

	private static final int CANCEL_CAS_RETRY = 3;

	/** 取消收敛写入的错误码，沿用运行时既有错误码风格（大写下划线）。 */
	private static final String CANCEL_ERROR_CODE = "RUN_CANCELLED";

	private final AgentRuntimeRunMapper runMapper;

	private final AgentRuntimeInterruptionMapper interruptionMapper;

	private final RuntimeStateService runtimeStateService;

	private final RuntimeEventService runtimeEventService;

	private final RuntimeOutboxService runtimeOutboxService;

	@Override
	public Long requestCancel(AgentRuntimeRun run, String reason, String requestedBy) {
		if (run == null || run.getId() == null) {
			throw CheckedException.badRequest("待取消的运行不存在");
		}
		Long epoch = runtimeStateService.bumpCancellationEpoch(run.getId());
		if (epoch == null) {
			// 已终态：取消与成功竞态下终态只有一个有效结果，此处不再覆盖
			return null;
		}
		insertInterruption(run.getTenantId(), run.getId(), run.getThreadId(), run.getRuntimeRequestId(), reason,
				requestedBy, epoch);
		RuntimeRunState current = RuntimeRunState.of(run.getState());
		boolean idle = current == RuntimeRunState.PENDING || current == RuntimeRunState.WAITING_APPROVAL
				|| current == RuntimeRunState.WAITING_INPUT;
		if (idle) {
			// 无在途执行体，直接落终态
			boolean converged = runtimeStateService.transitionRunWithRetry(run.getId(), RuntimeRunState.CANCELLED,
					RunStateChange.finished(null, CANCEL_ERROR_CODE, StringUtils.hasText(reason) ? reason : "用户取消"),
					CANCEL_CAS_RETRY);
			appendCancelEvent(run, RuntimeEventType.RUN_CANCELLED, epoch, reason);
			if (converged) {
				appendRunTerminalOutboxQuietly(run);
			}
		}
		else {
			// 在途执行体通过纪元比对发现取消并负责终态收敛
			runtimeStateService.transitionRunWithRetry(run.getId(), RuntimeRunState.CANCELLING, RunStateChange.none(),
					CANCEL_CAS_RETRY);
			appendCancelEvent(run, RuntimeEventType.RUN_CANCEL_REQUESTED, epoch, reason);
		}
		return epoch;
	}

	@Override
	public boolean requestCancelByRuntimeRequestId(String threadId, String runtimeRequestId, String reason) {
		if (!StringUtils.hasText(runtimeRequestId)) {
			return false;
		}
		AgentRuntimeRun run = runMapper.findByRuntimeRequestId(runtimeRequestId);
		if (run != null) {
			return requestCancel(run, reason, null) != null;
		}
		// 权威 Run 尚未落库（如纯遥测链路或落库前取消）：仅写中断记录，后续回源查询仍可发现取消
		insertInterruption(null, null, threadId, runtimeRequestId, reason, null, 0L);
		return true;
	}

	@Override
	public boolean isCancelRequested(String runtimeRequestId) {
		if (!StringUtils.hasText(runtimeRequestId)) {
			return false;
		}
		if (interruptionMapper.existsCancelByRuntimeRequestId(runtimeRequestId)) {
			return true;
		}
		AgentRuntimeRun run = runMapper.findByRuntimeRequestId(runtimeRequestId);
		if (run == null) {
			return false;
		}
		RuntimeRunState state = RuntimeRunState.of(run.getState());
		return state == RuntimeRunState.CANCELLING || state == RuntimeRunState.CANCELLED
				|| (run.getCancellationEpoch() != null && run.getCancellationEpoch() > 0);
	}

	/**
	 * 中断记录的 tenant_id 是冗余元数据：本方法可由内存注册表按 runtimeRequestId 写穿调用，
	 * 该链路跑在无用户会话的运行时线程上，确实取不到租户；记录的归属由 runId /
	 * runtimeRequestId（全局唯一 UUID）确定，且中断只会让运行停下来，落 0 不产生任何越权放行面。
	 */
	private void insertInterruption(String tenantId, Long runId, String threadId, String runtimeRequestId, String reason,
			String requestedBy, Long epoch) {
		AgentRuntimeInterruption interruption = AgentRuntimeInterruption.builder()
			.tenantId(tenantId)
			.runId(runId)
			.threadId(threadId)
			.runtimeRequestId(runtimeRequestId)
			.interruptionType(RuntimeInterruptionType.CANCEL.getValue())
			.state("REQUESTED")
			.reason(reason)
			.requestedBy(requestedBy)
			.cancellationEpoch(epoch == null ? 0L : epoch)
			.requestedAt(Instant.now())
			.createTime(Instant.now())
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
		interruptionMapper.insert(interruption);
	}

	/**
	 * 无在途执行体的取消在本方法内直接落终态，不经调度器的 {@code maybeFinishRun}，
	 * 因此终态 Outbox 必须在这里旁路补齐：少了这条，任务台账回写与 IM 终态通知都收不到取消。
	 * eventKey 与调度器、超时收敛同口径（run-terminal:runId:eventType），消费方按此幂等去重。
	 * 写入失败只记 error：终态已由 CAS 落库，附属记录缺失不回滚取消本身。
	 */
	private void appendRunTerminalOutboxQuietly(AgentRuntimeRun run) {
		String eventType = RuntimeEventType.RUN_CANCELLED.getValue();
		try {
			runtimeOutboxService.append(new RuntimeOutboxService.OutboxAppend(run.getTenantId(), null,
					run.getId(), null, eventType, "run-terminal:" + run.getId() + ":" + eventType,
					Map.of("state", RuntimeRunState.CANCELLED.getValue(), "errorCode", CANCEL_ERROR_CODE)));
		}
		catch (RuntimeException ex) {
			log.error("运行取消 Outbox 写入失败, 终态已收敛不受影响. runId={}, eventType={}", run.getId(), eventType, ex);
		}
	}

	private void appendCancelEvent(AgentRuntimeRun run, RuntimeEventType eventType, Long epoch, String reason) {
		try {
			runtimeEventService.append(run.getTenantId(), run.getId(), "run-cancel:" + epoch, eventType, null,
					Map.of("cancellationEpoch", epoch, "reason", reason == null ? "" : reason));
		}
		catch (RuntimeException ex) {
			// 事件是取消动作的附属记录：纪元与中断记录已生效，事件失败不应回滚取消本身
			log.error("取消事件追加失败. runId={}, epoch={}", run.getId(), epoch, ex);
		}
	}

}
