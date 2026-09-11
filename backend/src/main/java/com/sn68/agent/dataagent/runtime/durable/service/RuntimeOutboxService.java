/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import java.util.Map;

/**
 * 持久运行时 Outbox 服务：与业务状态变更同事务写入待派发消息（Outbox 模式），
 * 由 {@code RuntimeOutboxDispatcher} 轮询认领并发布为
 * {@link com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent}。
 *
 * <p>当前写入方：审批创建（APPROVAL_REQUESTED）、审批结果（APPROVAL_DECIDED）、
 * run 终态事件（RUN_SUCCEEDED / RUN_FAILED / RUN_CANCELLED / RUN_TIMED_OUT——由
 * RuntimeDagSchedulerImpl、RuntimeMirrorServiceImpl、RuntimeRunDaemonJob（超时收敛）与
 * RuntimeCancellationServiceImpl（空闲运行直接取消）在终态迁移 CAS 成功后旁路写入，
 * eventKey 为 run-terminal:runId:eventType 保证幂等）。</p>
 *
 * <p>当前 run 终态事件的消费方：ImRuntimeEventNotifier（IM 回传）、
 * TaskRunTerminalSyncListener（agent_task_run 台账终态回写）。</p>
 */
public interface RuntimeOutboxService {

	/**
	 * 追加一条待派发消息，参与调用方事务（Outbox 模式要求与状态变更同事务提交）。
	 * 序列化失败抛 CheckedException，由调用方事务整体回滚，失败关闭不落半截消息。
	 *
	 * @return 新建 outbox 记录ID
	 */
	Long append(OutboxAppend append);

	/**
	 * 认领并派发一批待派发消息（PENDING 或到期重试的 FAILED），返回本轮派发成功条数。
	 * 单条失败按重试计数退避，达到上限置 DEAD 并记录 error 日志；派发语义 at-least-once。
	 */
	int dispatchPending(int limit);

	/**
	 * Outbox 追加载体。
	 *
	 * @param tenantId 租户ID，空按 0 落库
	 * @param workspaceId 遗留字段（Workspace 已删除），调用方应传 null，信封可不带该键
	 * @param runId 所属运行ID，系统级消息可空
	 * @param eventId 来源事件ID（agent_runtime_event.id），可空
	 * @param eventType 事件类型，必填（落 message_type 列）
	 * @param eventKey 事件幂等键，必填，消费方按此去重
	 * @param payload 业务负载（已脱敏），可空
	 */
	record OutboxAppend(String tenantId, Long workspaceId, Long runId, Long eventId, String eventType, String eventKey,
			Map<String, Object> payload) {
	}

}
