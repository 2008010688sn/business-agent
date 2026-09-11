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
package com.sn68.agent.dataagent.employee.service;

import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.employee.dto.EmployeeConversationReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeConversationResp;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 数字员工对话 Facade 服务（PR-5 对话接缝）。
 *
 * <p>客户端只传员工ID + 输入；身份与租户由服务端从登录态解析。
 * rollout 未开启（默认）走默认 Guard 放行纯模型对话（CALLER 身份执行）；
 * rollout 开启且 Principal READY 时以员工 Principal token 委托执行（PRINCIPAL 模式），
 * token 经 Redis 缓存并按 auth_revision 比对刷新。</p>
 */
public interface EmployeeConversationService {

	/**
	 * 与数字员工进行一轮对话（同步单轮）。
	 * @param employeeId 数字员工ID
	 * @param request 对话请求（query 必填；sessionId 不传则新建会话）
	 * @return 回复 + 会话ID + 执行模式
	 */
	EmployeeConversationResp converse(Long employeeId, EmployeeConversationReq request);

	/**
	 * 与数字员工进行流式对话（复用现网 AgentResponse SSE 事件：message/complete/error/runtime_progress）。
	 * 首条 runtime_progress 携带 sessionId / runtimeRunId / executionMode，供前端接线履历。
	 * @param employeeId 数字员工ID
	 * @param request 对话请求（query 必填；sessionId 不传则新建会话）
	 * @return 现网协议 SSE 事件流
	 */
	Flux<ServerSentEvent<AgentResponse>> converseStream(Long employeeId, EmployeeConversationReq request);

}
