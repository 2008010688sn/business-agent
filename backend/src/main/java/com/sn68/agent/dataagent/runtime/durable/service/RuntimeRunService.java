/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeEventResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCreateReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import java.util.List;

/**
 * 持久运行时 Run 服务：幂等创建、查询、取消与恢复（cancel/resume 均走状态机 CAS）。
 */
public interface RuntimeRunService {

	/**
	 * 幂等创建 Run：(workspaceId, clientRequestId) 唯一，重复请求返回既有 Run，不重复创建。
	 */
	RuntimeRunResp create(String tenantId, String userId, RuntimeRunCreateReq req);

	IPage<RuntimeRunResp> page(String tenantId, RuntimeRunPageQueryReq req);

	RuntimeRunDetailResp detail(String tenantId, Long id);

	/**
	 * 本租户本会话当前非终态 CHAT Run；没有则 null。不校验会话属主，调用方必须先做属主校验。
	 */
	RuntimeRunResp findActiveChatRun(String tenantId, String threadId);

	/**
	 * 安静查询 Run 状态（不抛业务异常）：供记忆治理等旁路校验使用，
	 * run 不存在或不属于该租户返回 null，由调用方按各自迁移期语义裁决。
	 */
	RuntimeRunState findRunState(String tenantId, Long id);

	/**
	 * 系统级安静查询：按全局唯一 runtimeRequestId 取权威 Run ID（W7 记忆写入终态查证的接线入口）。
	 * 运行尚未纳入权威运行时（迁移期未镜像链路）返回 null，由调用方按迁移期语义放行。
	 */
	Long findRunIdByRuntimeRequestId(String runtimeRequestId);

	/**
	 * afterSeq 事件回放（SSE 断线恢复的数据源）。
	 */
	List<RuntimeEventResp> events(String tenantId, Long id, Long afterSeq, Integer limit);

	/**
	 * 取消：纪元递增 + 中断记录 + 状态机推进（空闲态直接 CANCELLED，在途态 CANCELLING）。
	 */
	void cancel(String tenantId, String userId, Long id, String reason);

	/**
	 * 恢复：仅 WAITING_APPROVAL / WAITING_INPUT 可恢复为 RUNNING。
	 */
	void resume(String tenantId, String userId, Long id);

	/**
	 * 数字员工人工对话开跑：幂等创建后立即置 RUNNING。
	 * <p>调用方必须传 {@code runMode=CHAT}（守护作业只拉起 AGENT_LOOP/DIRECT，不会二次执行对话）。</p>
	 */
	RuntimeRunResp startInteractiveRun(String tenantId, String userId, RuntimeRunCreateReq req);

	/**
	 * 数字员工人工对话成功收尾。失败只记日志，不抛给对话主路径。
	 */
	void succeedInteractiveRun(String tenantId, Long runId, String finalAnswer);

	/**
	 * 数字员工人工对话失败收尾。失败只记日志，不抛给对话主路径。
	 */
	void failInteractiveRun(String tenantId, Long runId, String errorCode, String errorMessage);

}
