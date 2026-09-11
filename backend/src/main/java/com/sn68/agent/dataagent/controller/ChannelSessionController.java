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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.channel.entity.AgentChannelSessionMapping;
import com.sn68.agent.dataagent.channel.enums.ChannelSessionErrorDict;
import com.sn68.agent.dataagent.channel.service.ChannelSessionMappingService;
import com.sn68.agent.dataagent.dto.channel.ChannelSessionPageQueryReq;
import com.sn68.agent.dataagent.dto.channel.ChannelSessionTakeoverReq;
import com.sn68.agent.dataagent.dto.channel.ChannelSessionTakeoverResp;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.service.chat.ChatService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询外部渠道会话并接管到内部会话。
 */
@RestController
@RequestMapping("/chat/channel-sessions")
@RequiredArgsConstructor
@Tag(name = "渠道会话", description = "查询外部渠道会话并接管到内部会话")
public class ChannelSessionController {

	private final ChannelSessionMappingService channelSessionMappingService;

	private final DataChatSessionService chatSessionService;

	private final ChatService chatService;

	private final DataAgentThinkingPermissionService thinkingPermissionService;

	@Operation(summary = "分页查询渠道会话", description = "分页查询渠道会话，用于渠道会话相关管理和运行场景。")
	@PostMapping("/page")
	public IPage<AgentChannelSessionMapping> query(@RequestBody(required = false) ChannelSessionPageQueryReq request) {
		thinkingPermissionService.requireCanViewAnyDiagnostics();
		return channelSessionMappingService.query(request);
	}

	@Operation(summary = "接管渠道会话", description = "接管渠道会话，用于渠道会话相关管理和运行场景。")
	@AccessLog(module = "渠道会话", description = "人工接管渠道会话")
	@PostMapping("/takeover")
	public ChannelSessionTakeoverResp takeover(@RequestBody ChannelSessionTakeoverReq request) {
		thinkingPermissionService.requireCanViewAnyDiagnostics();
		Long sessionId = request == null ? null : request.sessionId();
		if (sessionId == null) {
			throw CheckedException.badRequest("sessionId不能为空");
		}
		DataChatSession session = chatSessionService.findBySessionId(sessionId);
		if (session == null) {
			throw CheckedException.notFound(ChannelSessionErrorDict.SESSION_NOT_FOUND.getValue(),
					ChannelSessionErrorDict.SESSION_NOT_FOUND.getLabel());
		}
		return new ChannelSessionTakeoverResp(session, chatService.getSessionMessages(sessionId, session.getAgentId()));
	}

}
