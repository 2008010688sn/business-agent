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
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.service.DataAgentService;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.enums.AgentRequestSourceDict;
import com.sn68.agent.dataagent.service.chat.DataChatTurnService;
import com.sn68.agent.framework.commons.annotation.IgnoreGlobalResponse;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.commons.security.DataPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 提供 DataAgent 流式问答、运行停止和活跃运行查询。
 */
@Slf4j
@RestController
@AllArgsConstructor
@Tag(name = "DataAgent 运行", description = "提供 DataAgent 流式问答、运行停止和活跃运行查询")
public class DataAgentController {

	/** 复用智能体中心菜单码，不新增 IAM 权限资源；能打开智能体中心的角色即可问答。 */
	public static final String PERMISSION_AGENT_CENTER = "ai-agent_agent";

	private final DataAgentService agentService;

	private final DataChatTurnService chatTurnService;

	private final AuthenticationContext authenticationContext;

	@Operation(summary = "流式推送DataAgent 运行", description = "流式推送DataAgent 运行，用于DataAgent 运行相关管理和运行场景。")
	@IgnoreGlobalResponse(description = "DataAgent 流式问答")
	@PostMapping(value = "/stream/search", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<AgentResponse>> streamSearch(@RequestBody AgentRequest request) {
		applyAuthSnapshot(request);
		return agentService.streamSearch(request);
	}

	@Operation(summary = "修改DataAgent 运行", description = "修改DataAgent 运行，用于DataAgent 运行相关管理和运行场景。")
	@IgnoreGlobalResponse(description = "停止 DataAgent 流式运行")
	@PostMapping("/chat/runtime/stop")
	public boolean stopStream(@RequestBody AgentRequest request) {
		applyAuthSnapshot(request);
		if (request != null && !AgentRequestSourceDict.WEB_TAKEOVER.getValue().equalsIgnoreCase(request.getRequestSource())) {
			request.setRequestSource(AgentRequestSourceDict.WEB.getValue());
		}
		String threadId = requireText(request == null ? null : request.getThreadId(), "threadId");
		String runtimeRequestId = requireText(request == null ? null : request.getRuntimeRequestId(), "runtimeRequestId");
		agentService.authorizeRuntimeControl(request);
		boolean stopped = agentService.stopStreamProcessing(threadId, runtimeRequestId);
		if (stopped) {
			chatTurnService.cancelTurn(request);
		}
		return stopped;
	}

	@Operation(summary = "查询DataAgent 运行活跃运行", description = "查询DataAgent 运行活跃运行，用于DataAgent 运行相关管理和运行场景。")
	@PostMapping("/chat/runtime/active")
	public Object activeRuntime(@RequestBody AgentRequest request) {
		applyAuthSnapshot(request);
		String threadId = requireText(request == null ? null : request.getThreadId(), "threadId");
		agentService.authorizeRuntimeControl(request);
		return agentService.activeRuntime(threadId);
	}

	@Operation(summary = "查询会话当前未结束的 CHAT 运行", description = "刷新恢复入口：按 threadId 返回本用户会话上的非终态 CHAT Run，无则空。")
	@GetMapping("/chat/runtime/active-run")
	public RuntimeRunResp activeDurableChatRun(@RequestParam String threadId) {
		AgentRequest request = new AgentRequest();
		request.setThreadId(threadId);
		applyAuthSnapshot(request);
		agentService.authorizeRuntimeControl(request);
		return agentService.activeDurableChatRun(request);
	}

	private void applyAuthSnapshot(AgentRequest request) {
		if (request == null) {
			return;
		}
		request.setDataPermissionSnapshot(resolveDataPermissionSnapshot());
		request.setUserIdSnapshot(resolveUserIdSnapshot());
		request.setUserNickNameSnapshot(resolveUserNickNameSnapshot());
		request.setTenantIdSnapshot(resolveTenantIdSnapshot());
		request.setTenantCodeSnapshot(resolveTenantCodeSnapshot());
		request.setClientIdSnapshot(resolveClientIdSnapshot());
		request.setTeamIdsSnapshot(resolveTeamIdsSnapshot());
		if (!StringUtils.hasText(request.getRequestSource())) {
			request.setRequestSource(AgentRequestSourceDict.WEB.getValue());
		}
	}

	private DataPermission resolveDataPermissionSnapshot() {
		try {
			return authenticationContext.dataPermission();
		}
		catch (Exception ex) {
			log.warn("解析数据权限上下文失败, 本次运行不携带数据权限快照", ex);
			return null;
		}
	}

	private String resolveUserIdSnapshot() {
		try {
			return authenticationContext.userId();
		}
		catch (Exception ex) {
			log.warn("解析登录用户上下文失败, 本次运行不携带用户快照", ex);
			return null;
		}
	}

	private String requireText(String value, String fieldName) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest(fieldName + "不能为空");
		}
		return value;
	}

	private String resolveUserNickNameSnapshot() {
		try {
			return authenticationContext.nickName();
		}
		catch (Exception ex) {
			log.warn("解析登录用户昵称失败, 本次运行不携带昵称快照", ex);
			return null;
		}
	}

	private String resolveTenantIdSnapshot() {
		try {
			return authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析租户上下文失败, 本次运行不携带租户快照", ex);
			return null;
		}
	}

	private String resolveTenantCodeSnapshot() {
		try {
			return authenticationContext.tenantCode();
		}
		catch (Exception ex) {
			log.warn("解析租户编码失败, 本次运行不携带租户编码快照", ex);
			return null;
		}
	}

	private String resolveClientIdSnapshot() {
		try {
			return authenticationContext.clientId();
		}
		catch (Exception ex) {
			log.warn("解析客户端上下文失败, 本次运行不携带客户端快照", ex);
			return null;
		}
	}

	private java.util.List<String> resolveTeamIdsSnapshot() {
		try {
			return authenticationContext.teamIds();
		}
		catch (Exception ex) {
			log.warn("解析用户团队列表失败, 本次运行不携带团队快照", ex);
			return java.util.List.of();
		}
	}

}
