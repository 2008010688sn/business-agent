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
package com.sn68.agent.dataagent.service.permission;

import com.sn68.agent.dataagent.agentscope.runtime.AgentUiResponseSupport;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.dto.ChatMessageReq;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 集中判断当前用户可见的思考、调用链和答案来源诊断权限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAgentThinkingPermissionService {

	private static final String MESSAGE_TYPE_THINKING = "thinking";

	private static final String THINKING_HTML_BLOCK = "agent-thinking-block";

	private static final String THINKING_HTML_GROUP = "agent-thinking-group";

	private static final String RUNTIME_NODE_NAME = "AgentScopeRuntime";

	private static final String REPORT_NODE_NAME = "ReportGeneratorNode";

	private final DataAgentProperties properties;

	private final AuthenticationContext authenticationContext;

	/**
	 * 校验DataAgentThinking权限。
	 */
	public boolean canViewThinking() {
		return canView(resolveThinkingPermissionCode());
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public boolean canViewAnswerSource() {
		return canView(resolveAnswerSourcePermissionCode());
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public boolean canViewCallChain() {
		return canView(resolveCallChainPermissionCode());
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public boolean canViewAnswerExplain() {
		return canViewThinking() || canViewAnswerSource();
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public boolean canViewAnyDiagnostics() {
		return canViewAnswerExplain() || canViewCallChain();
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public boolean canViewUsage() {
		return canView(resolveUsageViewPermissionCode());
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public boolean canManageUsage() {
		return canView(resolveUsageManagePermissionCode());
	}

	private boolean canView(String permissionCode) {
		try {
			if (authenticationContext.anonymous() || authenticationContext.getContext() == null) {
				return false;
			}
			if (!StringUtils.hasText(permissionCode)) {
				return false;
			}
			if (UserType.isAdmin(authenticationContext.userType())) {
				return true;
			}
			List<String> permissions = authenticationContext.funcPermissionList();
			return permissions != null && permissions.contains(permissionCode);
		}
		catch (Exception ex) {
			log.warn("解析 DataAgent 诊断权限失败, 按无权限处理, permissionCode={}", permissionCode, ex);
			return false;
		}
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public void requireCanViewThinking() {
		if (!canViewThinking()) {
			throw CheckedException.forbidden();
		}
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public void requireCanViewAnswerSource() {
		if (!canViewAnswerSource()) {
			throw CheckedException.forbidden();
		}
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public void requireCanViewCallChain() {
		if (!canViewCallChain()) {
			throw CheckedException.forbidden();
		}
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public void requireCanViewAnswerExplain() {
		if (!canViewAnswerExplain()) {
			throw CheckedException.forbidden();
		}
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public void requireCanViewAnyDiagnostics() {
		if (!canViewAnyDiagnostics()) {
			throw CheckedException.forbidden();
		}
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public void requireCanViewUsage() {
		if (!canViewUsage()) {
			throw CheckedException.forbidden();
		}
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public void requireCanManageUsage() {
		if (!canManageUsage()) {
			throw CheckedException.forbidden();
		}
	}

	/**
	 * 处理DataAgentThinking权限。
	 */
	public boolean shouldExposeStreamEvent(ServerSentEvent<AgentResponse> event, boolean canViewThinking) {
		if (canViewThinking || event == null || event.data() == null) {
			return true;
		}
		if (AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS.equals(event.event())) {
			return true;
		}
		return shouldExposeResponse(event.data(), false);
	}

	/**
	 * 处理DataAgentThinking权限。
	 */
	public boolean shouldExposeResponse(AgentResponse response, boolean canViewThinking) {
		return canViewThinking || !isThinkingResponse(response);
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public boolean isThinkingMessage(DataChatMessage message) {
		return message != null && isThinkingMessage(message.getMessageType(), message.getContent());
	}

	/**
	 * 校验DataAgentThinking权限。
	 */
	public boolean isThinkingMessage(ChatMessageReq message) {
		return message != null && isThinkingMessage(message.getMessageType(), message.getContent());
	}

	private boolean isThinkingMessage(String messageType, String content) {
		return MESSAGE_TYPE_THINKING.equals(normalize(messageType)) || containsThinkingHtml(content);
	}

	private boolean isThinkingResponse(AgentResponse response) {
		if (response == null || response.isComplete() || response.isError()) {
			return false;
		}
		if (AgentUiResponseSupport.isStructuredUiResponse(response)) {
			Map<String, Object> metadata = response.getMetadata();
			log.debug(
					"Expose structured UI response. messageType={}, nodeName={}, runtimeRequestId={}",
					metadata.get("messageType"),
					response.getNodeName(), metadata.get("runtimeRequestId"));
			return false;
		}
		if (response.getTextType() == TextType.RESULT_SET) {
			return false;
		}
		String nodeName = response.getNodeName();
		if (RUNTIME_NODE_NAME.equals(nodeName) || REPORT_NODE_NAME.equals(nodeName)) {
			return false;
		}
		return true;
	}

	private boolean containsThinkingHtml(String content) {
		if (!StringUtils.hasText(content)) {
			return false;
		}
		String normalizedContent = content.toLowerCase(Locale.ROOT);
		return normalizedContent.contains(THINKING_HTML_BLOCK) || normalizedContent.contains(THINKING_HTML_GROUP);
	}

	private String resolveThinkingPermissionCode() {
		DataAgentProperties.Security security = properties.getSecurity();
		return security == null ? null : security.getThinkingPermission();
	}

	private String resolveAnswerSourcePermissionCode() {
		DataAgentProperties.Security security = properties.getSecurity();
		return security == null ? null : security.getAnswerSourcePermission();
	}

	private String resolveCallChainPermissionCode() {
		DataAgentProperties.Security security = properties.getSecurity();
		return security == null ? null : security.getCallChainPermission();
	}

	private String resolveUsageViewPermissionCode() {
		DataAgentProperties.Security security = properties.getSecurity();
		return security == null ? null : security.getUsageViewPermission();
	}

	private String resolveUsageManagePermissionCode() {
		DataAgentProperties.Security security = properties.getSecurity();
		return security == null ? null : security.getUsageManagePermission();
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

}
