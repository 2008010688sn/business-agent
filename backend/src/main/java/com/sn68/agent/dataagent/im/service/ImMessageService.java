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
package com.sn68.agent.dataagent.im.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.im.dto.ImMessageDTO;
import com.sn68.agent.dataagent.im.dto.ImMessagePageQuery;
import com.sn68.agent.dataagent.im.entity.AgentImMessage;
import com.sn68.agent.dataagent.im.repository.AgentImMessageMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * IM 消息日志服务（管理端，仅返回当前登录租户数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImMessageService {

	private final AgentImMessageMapper messageMapper;

	private final AuthenticationContext authenticationContext;

	/**
	 * 处理ImMessage。
	 */
	public IPage<ImMessageDTO> page(ImMessagePageQuery request) {
		ImMessagePageQuery pageRequest = request == null ? new ImMessagePageQuery() : request;
		return messageMapper.selectDiagnosticsPage(pageRequest.buildPage(), requireCurrentTenantId(), pageRequest)
			.convert(this::toDTO);
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次 IM 消息查询", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失, 无法查询 IM 消息");
		}
		return tenantId;
	}

	private ImMessageDTO toDTO(AgentImMessage message) {
		return new ImMessageDTO(message.getId(), message.getProvider(), message.getConnectorCode(),
				message.getConversationType(), message.getExternalConversationId(), message.getExternalUserId(),
				message.getDirection(), message.getMessageType(), message.getContent(), message.getResponseContent(),
				message.getAgentId(), message.getSessionId(), message.getRuntimeRequestId(), message.getUserId(),
				message.getStatus(), message.getErrorMessage(), message.getCreateTime());
	}

}
