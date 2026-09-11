/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.agentscope.service;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.session.AgentRuntimeRegistry.RuntimeExecutionStateView;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.util.function.Consumer;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * DataAgent 运行时服务契约：提供流式/同步两种执行入口，以及运行中请求的停止与状态查询。
 */
public interface DataAgentService {

	/**
	 * 以 SSE 流式方式执行一次 Agent 请求，返回增量响应事件流。
	 */
	Flux<ServerSentEvent<AgentResponse>> streamSearch(AgentRequest agentRequest);

	/**
	 * 同步执行一次 Agent 请求并返回最终文本结果。
	 */
	String executeAgentOnce(AgentRequest agentRequest);

	/**
	 * 执行同步请求并收集当前请求内生成的公开 UI 消息。
	 */
	String executeAgentOnce(AgentRequest agentRequest, Consumer<AgentUiMessage> uiCollector);

	/**
	 * 在指定 sink 上执行流式处理，由调用方管理 SSE 通道的生命周期。
	 */
	void graphStreamProcess(Sinks.Many<ServerSentEvent<AgentResponse>> sink, AgentRequest agentRequest);

	/**
	 * 请求停止指定会话线程上正在执行的流式处理；返回是否成功发出停止信号。
	 *
	 * <p>
	 * 内部取消（SSE 断开、编排级联、实时语音）直接调用本方法。HTTP 停流/查询活跃运行必须先
	 * {@link #authorizeRuntimeControl(AgentRequest)}，避免任意登录用户凭 threadId 停别人的流。
	 */
	boolean stopStreamProcessing(String threadId, String runtimeRequestId);

	/**
	 * HTTP 停流与活跃运行查询的会话归属校验：Web 会话须属于当前用户；渠道会话仅允许
	 * {@code WEB_TAKEOVER} 且具备诊断权限的账号。会话不存在时失败关闭。
	 */
	void authorizeRuntimeControl(AgentRequest request);

	/**
	 * 查询指定会话线程当前活跃的运行时执行状态；无活跃执行时返回 null。
	 */
	RuntimeExecutionStateView activeRuntime(String threadId);

	/**
	 * 查询本会话当前非终态 CHAT Run。须先 {@link #authorizeRuntimeControl(AgentRequest)}。
	 */
	RuntimeRunResp activeDurableChatRun(AgentRequest request);

}
