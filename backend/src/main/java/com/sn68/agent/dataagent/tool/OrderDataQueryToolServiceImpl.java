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
package com.sn68.agent.dataagent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRequestSnapshotSupport;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
/**
 * 订单数据查询工具实现：以受控执行资源（order.data.query）形态向 Agent 暴露订单查询能力。
 */
@Service
@RequiredArgsConstructor
public class OrderDataQueryToolServiceImpl implements DataQueryToolService {

	private static final String RESOURCE_ORDER_DATA_QUERY = "order.data.query";

	private static final String DEFAULT_AGENT_KEYWORD = "\u8ba2\u5355";

	private static final String DEFAULT_SESSION_TITLE = "\u8ba2\u5355\u5206\u6790\u67e5\u8be2";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ObjectProvider<AgentInvocationService> agentInvocationServiceProvider;

	private final DataAgentMapper dataAgentMapper;

	private final DataChatSessionService chatSessionService;

	private final AuthenticationContext authenticationContext;

	private final ObjectMapper objectMapper;

	@Override
	public Map<String, Object> query(AgentExecutionResource resource, Map<String, Object> arguments) {
		if (resource == null || !RESOURCE_ORDER_DATA_QUERY.equals(resource.getResourceKey())) {
			throw CheckedException.badRequest("Unsupported data query resource: "
					+ (resource == null ? null : resource.getResourceKey()));
		}
		Map<String, Object> safeArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
		String question = firstText(stringValue(safeArguments.get("question")), stringValue(safeArguments.get("query")));
		if (!StringUtils.hasText(question)) {
			throw CheckedException.badRequest("question is required.");
		}
		Map<String, Object> extConfig = readJsonObject(resource.getExtConfig());
		Long agentId = resolveAgentId(safeArguments, extConfig);
		String sessionId = resolveSessionId(agentId, safeArguments);
		String runtimeRequestId = firstText(stringValue(safeArguments.get("runtimeRequestId")),
				"mcp-order-data-query-" + UUID.randomUUID());
		AgentRequest childRequest = AgentRequest.builder()
			.agentId(String.valueOf(agentId))
			.threadId(sessionId)
			.runtimeRequestId(runtimeRequestId)
			.query(buildQuestion(question, safeArguments))
			.responseMode(firstText(stringValue(safeArguments.get("responseMode")),
					stringValue(extConfig.get("responseMode")), "normal"))
			.isolatedMemory(true)
			.build();
		AgentRequestSnapshotSupport.inheritFromArguments(childRequest, safeArguments, objectMapper);
		String answer = agentInvocationServiceProvider.getObject()
			.invoke(childRequest);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("success", true);
		result.put("resourceType", resource.getResourceType());
		result.put("resourceKey", resource.getResourceKey());
		result.put("readonly", true);
		result.put("agentId", agentId);
		result.put("sessionId", sessionId);
		result.put("runtimeRequestId", runtimeRequestId);
		result.put("question", question);
		result.put("answer", answer);
		return result;
	}

	private Long resolveAgentId(Map<String, Object> arguments, Map<String, Object> extConfig) {
		Long explicit = parseLong(stringValue(arguments.get("agentId")));
		if (explicit != null) {
			return explicit;
		}
		Long configured = parseLong(stringValue(extConfig.get("defaultAgentId")));
		if (configured != null) {
			return configured;
		}
		List<DataAgent> candidates = dataAgentMapper.findByConditions(null,
				firstText(stringValue(extConfig.get("agentKeyword")), DEFAULT_AGENT_KEYWORD));
		return candidates.stream()
			.filter(agent -> agent != null && !"offline".equalsIgnoreCase(agent.getStatus()))
			.map(DataAgent::getId)
			.findFirst()
			.orElseThrow(() -> CheckedException.badRequest(
					"Order data query agent is not configured. Set extConfig.defaultAgentId for order.data.query."));
	}

	private String resolveSessionId(Long agentId, Map<String, Object> arguments) {
		String sessionId = firstText(stringValue(arguments.get("sessionId")), stringValue(arguments.get("threadId")));
		if (parseLong(sessionId) != null) {
			return sessionId;
		}
		DataChatSession session = chatSessionService.createSession(agentId, DEFAULT_SESSION_TITLE,
				currentUserId(arguments));
		if (session == null || session.getId() == null) {
			throw CheckedException.badRequest("Failed to create order data query session.");
		}
		return String.valueOf(session.getId());
	}

	private String buildQuestion(String question, Map<String, Object> arguments) {
		StringBuilder prompt = new StringBuilder();
		prompt.append("Analyze order data in read-only mode using the current user, tenant, data permissions, ");
		prompt.append("and masking rules. Do not perform writes and do not expose raw SQL.");
		prompt.append('\n').append("User question: ").append(question);
		Object dateRange = arguments.get("dateRange");
		if (dateRange != null) {
			prompt.append('\n').append("Date range: ").append(stringValue(dateRange));
		}
		Object limit = arguments.get("limit");
		if (limit != null) {
			prompt.append('\n').append("Result row limit: ").append(stringValue(limit));
		}
		return prompt.toString();
	}

	private Long currentUserId(Map<String, Object> arguments) {
		Long snapshotUserId = parseLong(AgentRequestSnapshotSupport.userId(arguments));
		if (snapshotUserId != null) {
			return snapshotUserId;
		}
		try {
			if (authenticationContext == null || authenticationContext.anonymous()) {
				return null;
			}
			return parseLong(authenticationContext.userId());
		}
		catch (Exception ex) {
			log.debug("Failed to resolve current user for order data query session", ex);
			return null;
		}
	}

	private Map<String, Object> readJsonObject(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(value, MAP_TYPE);
			return map == null ? Map.of() : new LinkedHashMap<>(map);
		}
		catch (Exception ex) {
			log.debug("Failed to parse order data query ext config", ex);
			return Map.of();
		}
	}

	private Long parseLong(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return Long.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

}
