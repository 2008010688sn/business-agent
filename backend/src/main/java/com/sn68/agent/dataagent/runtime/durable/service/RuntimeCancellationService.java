/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;

/**
 * 持久运行时取消服务：取消标记的写穿（内存注册表 → DB）与跨节点回源。
 *
 * <p>取消动作 = cancellation_epoch 递增 + agent_runtime_interruption 落记录 + Run 状态机推进
 * （PENDING/WAITING_* → CANCELLED，RUNNING → CANCELLING）+ 取消事件。</p>
 */
public interface RuntimeCancellationService {

	/**
	 * 按权威 Run 取消（API 状态机路径）。
	 *
	 * @return 本次取消对应的纪元；运行已终态返回 null
	 */
	Long requestCancel(AgentRuntimeRun run, String reason, String requestedBy);

	/**
	 * 按运行时请求ID取消（内存注册表写穿路径，系统级，无租户上下文）。
	 * 未找到权威 Run 时仅落中断记录，等 run 落库后仍可被回源查询发现。
	 *
	 * @return 是否成功写入取消标记
	 */
	boolean requestCancelByRuntimeRequestId(String threadId, String runtimeRequestId, String reason);

	/**
	 * 跨节点回源：该运行时请求ID是否已有取消标记（中断记录或权威 Run 已进入取消轨道）。
	 */
	boolean isCancelRequested(String runtimeRequestId);

}
