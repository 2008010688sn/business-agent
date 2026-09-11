/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeEvent;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import java.util.List;
import java.util.Map;

/**
 * 持久运行时事件服务：按 run 追加单调 seq 事件，提供 afterSeq 回放。
 *
 * <p>事件流使用持久化序列而非 JVM 内存 Sink：seq 在数据库端以 MAX(seq)+1 原子分配并受
 * (run_id, seq) 唯一约束保护；(run_id, event_key) 唯一约束保证重复追加幂等。
 * SSE 断线后客户端携 afterSeq 重放即可做到无遗漏、无乱序、无重复。</p>
 */
public interface RuntimeEventService {

	/**
	 * 追加事件（event_key 幂等，重复追加返回 false）。payload 允许为空。
	 */
	boolean append(String tenantId, Long runId, String eventKey, RuntimeEventType eventType, String stepKey,
			Map<String, Object> payload);

	/**
	 * 执行节点追加：必须携带当前 Run 租约 fence。缺 fence 拒绝；fence 失配拒绝；event_key 已存在返回 false。
	 */
	boolean appendFenced(String tenantId, Long runId, String eventKey, RuntimeEventType eventType, String stepKey,
			Map<String, Object> payload, Long fenceToken, String leaseOwner);

	/**
	 * afterSeq 回放：返回 seq 大于游标的事件，按 seq 升序。afterSeq 为空按 0 处理（全量回放）。
	 */
	List<AgentRuntimeEvent> replayAfter(Long runId, Long afterSeq, int limit);

	/**
	 * 当前最大 seq，无事件返回 0。
	 */
	long latestSeq(Long runId);

}
