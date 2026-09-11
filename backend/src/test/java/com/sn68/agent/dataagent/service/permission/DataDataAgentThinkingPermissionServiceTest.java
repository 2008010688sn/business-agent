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

import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.agentscope.runtime.AgentUiResponseSupport;
import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import com.sn68.agent.dataagent.dto.ChatMessageReq;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataDataAgentThinkingPermissionServiceTest {

	private static final String THINKING_PERMISSION = "dataagent:thinking:view";

	private static final String ANSWER_SOURCE_PERMISSION = "dataagent:answer-source:view";

	private static final String CALL_CHAIN_PERMISSION = "dataagent:call-chain:view";

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private DataAgentProperties properties;

	private DataAgentThinkingPermissionService service;

	@BeforeEach
	void setUp() {
		properties = new DataAgentProperties();
		service = new DataAgentThinkingPermissionService(properties, authenticationContext);
	}

	@Test
	void canViewThinking_deniesWhenPermissionConfigIsEmpty() {
		when(authenticationContext.anonymous()).thenReturn(false);
		when(authenticationContext.getContext()).thenReturn(new Object());
		when(authenticationContext.userType()).thenReturn(UserType.IN);

		assertFalse(service.canViewThinking());
		assertFalse(service.canViewAnswerSource());
		assertFalse(service.canViewCallChain());
	}

	@Test
	void canViewThinking_deniesAdminWhenPermissionConfigIsEmpty() {
		when(authenticationContext.anonymous()).thenReturn(false);
		when(authenticationContext.getContext()).thenReturn(new Object());
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);

		assertFalse(service.canViewThinking());
		assertFalse(service.canViewAnswerSource());
		assertFalse(service.canViewCallChain());
	}

	@Test
	void canViewThinking_deniesWhenContextIsMissing() {
		configurePermission();
		when(authenticationContext.anonymous()).thenReturn(false);
		when(authenticationContext.getContext()).thenReturn(null);

		assertFalse(service.canViewThinking());
		assertFalse(service.canViewAnswerSource());
		assertFalse(service.canViewCallChain());
	}

	@Test
	void canViewThinking_allowsAdminWhenPermissionIsConfigured() {
		configureLoggedInUser(UserType.PLATFORM_ADMIN, List.of());
		assertTrue(service.canViewThinking());
		assertTrue(service.canViewAnswerSource());
		assertTrue(service.canViewCallChain());

		configureLoggedInUser(UserType.TENANT_ADMIN, List.of());
		assertTrue(service.canViewThinking());
		assertTrue(service.canViewAnswerSource());
		assertTrue(service.canViewCallChain());
	}

	@Test
	void canViewThinking_checksNormalUserFunctionPermission() {
		configureLoggedInUser(UserType.IN, List.of("other", THINKING_PERMISSION));
		assertTrue(service.canViewThinking());

		configureLoggedInUser(UserType.IN, List.of("other"));
		assertFalse(service.canViewThinking());
	}

	@Test
	void canViewDiagnostics_checksIndependentNormalUserFunctionPermissions() {
		configureLoggedInUser(UserType.IN, List.of(THINKING_PERMISSION));
		assertTrue(service.canViewThinking());
		assertFalse(service.canViewAnswerSource());
		assertFalse(service.canViewCallChain());

		configureLoggedInUser(UserType.IN, List.of(ANSWER_SOURCE_PERMISSION));
		assertFalse(service.canViewThinking());
		assertTrue(service.canViewAnswerSource());
		assertFalse(service.canViewCallChain());

		configureLoggedInUser(UserType.IN, List.of(CALL_CHAIN_PERMISSION));
		assertFalse(service.canViewThinking());
		assertFalse(service.canViewAnswerSource());
		assertTrue(service.canViewCallChain());
	}

	@Test
	void shouldExposeResponse_hidesIntermediateNodesForUserWithoutPermission() {
		assertFalse(service.shouldExposeResponse(response("planner-reasoning", TextType.TEXT), false));
		assertFalse(service.shouldExposeResponse(response("tool:sql_guard", TextType.TEXT), false));
		assertFalse(service.shouldExposeResponse(response("SqlPlannerNode", TextType.SQL), false));
		assertTrue(service.shouldExposeResponse(response("AgentScopeRuntime", TextType.TEXT), false));
		assertTrue(service.shouldExposeResponse(response("ReportGeneratorNode", TextType.MARK_DOWN), false));
		assertTrue(service.shouldExposeResponse(response("DatasourceExplorerNode", TextType.RESULT_SET), false));
	}

	@Test
	void shouldExposeResponse_allowsSkillFlowUiWithoutThinkingPermission() {
		assertTrue(service.shouldExposeResponse(skillFlowResponse(), false));
		assertFalse(service.shouldExposeResponse(response("SkillRuntime", TextType.JSON), false));
	}

	@Test
	void isThinkingMessage_matchesNewTypeAndLegacyHtml() {
		ChatMessageReq dto = new ChatMessageReq();
		dto.setMessageType("thinking");
		assertTrue(service.isThinkingMessage(dto));

		DataChatMessage legacyHtml = DataChatMessage.builder()
			.messageType("html")
			.content("<details class=\"agent-thinking-block\"></details>")
			.build();
		assertTrue(service.isThinkingMessage(legacyHtml));
	}

	private void configureLoggedInUser(UserType userType, List<String> permissions) {
		configurePermission();
		when(authenticationContext.anonymous()).thenReturn(false);
		when(authenticationContext.getContext()).thenReturn(new Object());
		when(authenticationContext.userType()).thenReturn(userType);
		when(authenticationContext.funcPermissionList()).thenReturn(permissions);
	}

	private void configurePermission() {
		properties.getSecurity().setThinkingPermission(THINKING_PERMISSION);
		properties.getSecurity().setAnswerSourcePermission(ANSWER_SOURCE_PERMISSION);
		properties.getSecurity().setCallChainPermission(CALL_CHAIN_PERMISSION);
	}

	private AgentResponse response(String nodeName, TextType textType) {
		return AgentResponse.builder().nodeName(nodeName).textType(textType).text("content").build();
	}

	private AgentResponse skillFlowResponse() {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("messageType", AgentSessionConstant.MESSAGE_TYPE_SKILL_FLOW);
		metadata.put("uiSchemaVersion", AgentUiResponseSupport.UI_SCHEMA_VERSION);
		metadata.put("agentUi", Map.of("schemaVersion", AgentUiResponseSupport.UI_SCHEMA_VERSION, "kind", "skill-flow"));
		metadata.put("runtimeRequestId", "runtime-1");
		return AgentResponse.builder()
			.nodeName("SkillRuntime")
			.textType(TextType.JSON)
			.text("请选择客户")
			.metadata(metadata)
			.build();
	}

}
