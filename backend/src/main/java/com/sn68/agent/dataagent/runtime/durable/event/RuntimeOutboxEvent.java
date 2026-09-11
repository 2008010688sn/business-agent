/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.event;

/**
 * 持久运行时 Outbox 派发事件（Spring ApplicationEvent 负载）。
 *
 * <p>由 Outbox 派发器把 agent_runtime_outbox 中待派发行发布为本事件，进程内监听方
 * （IM 通知、任务续发等）按 eventType 过滤消费。<b>本类是跨代理协作契约：包名、类名、
 * 字段名与字段类型不得变更</b>；派发语义为 at-least-once，监听方必须按 eventKey 幂等。</p>
 *
 * @param tenantId 租户ID，0 表示未解析到租户
 * @param workspaceId AI 工作空间ID，可空
 * @param runId 所属运行ID（agent_runtime_run.id 的字符串形式），系统级事件可空
 * @param eventType 事件类型（如 APPROVAL_REQUESTED、APPROVAL_DECIDED、RUN_SUCCEEDED）
 * @param eventKey 事件幂等键，消费方按此去重
 * @param payloadJson 业务负载 JSON（已脱敏，不含凭据与原始参数）
 */
public record RuntimeOutboxEvent(
		String tenantId,
		Long workspaceId,
		String runId,
		String eventType,
		String eventKey,
		String payloadJson) {
}
