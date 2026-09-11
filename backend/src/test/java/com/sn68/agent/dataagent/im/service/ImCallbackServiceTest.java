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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.channel.entity.AgentChannelSessionMapping;
import com.sn68.agent.dataagent.channel.service.ChannelSessionMappingService;
import com.sn68.agent.dataagent.im.adapter.ImAdapter;
import com.sn68.agent.dataagent.im.adapter.ImAdapterRegistry;
import com.sn68.agent.dataagent.im.dto.ImCallbackMessage;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.entity.AgentImMessage;
import com.sn68.agent.dataagent.im.entity.AgentImUserIdentity;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.im.repository.AgentImConnectorMapper;
import com.sn68.agent.dataagent.im.repository.AgentImConversationBindingMapper;
import com.sn68.agent.dataagent.im.repository.AgentImMessageMapper;
import com.sn68.agent.dataagent.im.repository.AgentImUserIdentityMapper;
import com.sn68.agent.dataagent.iam.dto.DelegatedAuthContextResp;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.dataagent.service.agent.AgentInvocationResult;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import com.sn68.agent.dataagent.tool.ToolInvocationException;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.DataPermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImCallbackServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	// 本类断言的是回调幂等与出站失败标记的落库形态，需保持明文可比；
	// S-6 已把加密默认改为开启（缺 key 即启动失败），故这里显式关掉。
	// 加密默认值与缺 key 时的启动失败由 SensitiveConfigCryptoServiceTest 覆盖。
	private final DataAgentProperties properties = cryptoDisabledProperties();

	private final NotificationJsonSupport jsonSupport = new NotificationJsonSupport(objectMapper,
			new SensitiveConfigCryptoService(properties));

	private static DataAgentProperties cryptoDisabledProperties() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getCrypto().setEnabled(false);
		return properties;
	}

	@Test
	void outboundMessageUsesRuntimeRequestIdForIdempotencyKey() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		List<AgentImMessage> insertedMessages = new ArrayList<>();
		List<String> sentMessages = new ArrayList<>();
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		doAnswer(invocation -> {
			AgentImMessage message = invocation.getArgument(0);
			insertedMessages.add(message);
			return 1;
		}).when(messageMapper).insert(any(AgentImMessage.class));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		when(adapter.responsePayload("ok")).thenReturn(Map.of("success", true));

		AgentImUserIdentityMapper identityMapper = mock(AgentImUserIdentityMapper.class);
		AgentImUserIdentity identity = new AgentImUserIdentity();
		identity.setUserId("42");
		when(identityMapper.findEnabledByExternalUser("100", ImConstants.PROVIDER_DINGTALK,
				"dingtalk-customer-service", "staff-1"))
			.thenReturn(identity);

		DelegatedAuthContextService delegatedAuthContextService = mock(DelegatedAuthContextService.class);
		DelegatedAuthContextResp authContext = new DelegatedAuthContextResp();
		authContext.setUserId("42");
		authContext.setTenantId("100");
		authContext.setAccessToken("token");
		authContext.setDataPermission(new DataPermission());
		when(delegatedAuthContextService.issue(any())).thenReturn(DelegatedAuthContextService.IssueResult.ok(authContext));
		when(delegatedAuthContextService.executeWith(any(), any())).thenAnswer(invocation -> invocation
			.<java.util.function.Supplier<?>>getArgument(1)
			.get());

		DataChatSessionService chatSessionService = chatSessionService();
		ChannelSessionMappingService channelSessionMappingService = mock(ChannelSessionMappingService.class);
		AgentChannelSessionMapping mapping = AgentChannelSessionMapping.builder()
			.agentId(100L)
			.sessionId(900L)
			.build();
		when(channelSessionMappingService.getOrCreate(any())).thenReturn(mapping);

		AgentInvocationService agentInvocationService = mock(AgentInvocationService.class);
		when(agentInvocationService.invokeDetailed(any())).thenAnswer(invocation -> {
			AgentRequest request = invocation.getArgument(0);
			assertEquals("900", request.getThreadId());
			assertEquals("dingtalk-customer-service", request.getConnectorCode());
			assertTrue(request.getRuntimeRequestId().startsWith("im-"));
			return new AgentInvocationResult("ok", null);
		});

		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), identityMapper, messageMapper, adapterRegistry, jsonSupport,
				objectMapper, channelSessionMappingService, chatSessionService, agentInvocationService,
				mock(DingTalkCredentialService.class), delegatedAuthContextService, runtimeConfigService());

		AgentImConnector connector = connector();
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"msgxxx", ImConstants.CONVERSATION_GROUP, "cid-1", "staff-1", null, null, "TEXT", "@bot query logs",
				true, "https://example.com/reply", Map.of("msgId", "msgxxx"));

		doAnswer(invocation -> {
			sentMessages.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		callbackService.handleStreamMessage(connector, message);

		assertEquals(2, insertedMessages.size());
		assertEquals(2, sentMessages.size());
		assertEquals("正在思考中，请耐心等候...", sentMessages.get(0));
		assertEquals("ok", sentMessages.get(1));
		AgentImMessage inbound = insertedMessages.get(0);
		AgentImMessage outbound = insertedMessages.get(1);
		assertEquals(ImConstants.DIRECTION_INBOUND, inbound.getDirection());
		assertEquals(ImConstants.DIRECTION_OUTBOUND, outbound.getDirection());
		// W6 租户化：IM 幂等键以连接器租户为前缀，避免不同租户同名连接器的幂等键碰撞。
		assertEquals("100:DINGTALK:dingtalk-customer-service:msgxxx", inbound.getIdempotencyKey());
		assertTrue(outbound.getIdempotencyKey().startsWith("100:DINGTALK:dingtalk-customer-service:OUT:im-"));
		assertNotEquals(inbound.getIdempotencyKey(), outbound.getIdempotencyKey());
		assertEquals("msgxxx", outbound.getExternalMessageId());
		verify(agentInvocationService, never()).invoke(any());
	}

	@Test
	void skippedMessageDoesNotSendThinkingMessage() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		List<String> sentMessages = new ArrayList<>();
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		doAnswer(invocation -> 1).when(messageMapper).insert(any(AgentImMessage.class));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		doAnswer(invocation -> {
			sentMessages.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		AgentImUserIdentityMapper identityMapper = mock(AgentImUserIdentityMapper.class);
		when(identityMapper.findEnabledByExternalUser(any(), any(), any(), any())).thenReturn(null);

		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), identityMapper, messageMapper, adapterRegistry, jsonSupport,
				objectMapper, mock(ChannelSessionMappingService.class), mock(DataChatSessionService.class),
				mock(AgentInvocationService.class), mock(DingTalkCredentialService.class),
				mock(DelegatedAuthContextService.class), runtimeConfigService());

		AgentImConnector connector = connector();
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"msg-skip", ImConstants.CONVERSATION_GROUP, "cid-1", "staff-1", null, null, "IMAGE", "img",
				true, "https://example.com/reply", Map.of("msgId", "msg-skip"));

		callbackService.handleStreamMessage(connector, message);

		assertTrue(sentMessages.isEmpty());
		verify(adapter, never()).sendReply(any(), any(), anyString());
	}

	@Test
	void duplicateInsertRaceDoesNotInvokeAgentAgain() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		AgentImMessage existing = AgentImMessage.builder()
			.idempotencyKey("DINGTALK:dingtalk-customer-service:msg-race")
			.status(ImConstants.MESSAGE_STATUS_PROCESSING)
			.runtimeRequestId("im-existing")
			.build();
		when(messageMapper.findByIdempotencyKey(anyString())).thenReturn(null, existing);
		doAnswer(invocation -> {
			throw new DuplicateKeyException("duplicate");
		}).when(messageMapper).insert(any(AgentImMessage.class));
		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		AgentInvocationService invocationService = mock(AgentInvocationService.class);
		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), mock(AgentImUserIdentityMapper.class), messageMapper,
				adapterRegistry, jsonSupport, objectMapper, mock(ChannelSessionMappingService.class),
				mock(DataChatSessionService.class), invocationService, mock(DingTalkCredentialService.class),
				mock(DelegatedAuthContextService.class), runtimeConfigService());
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK,
				"dingtalk-customer-service", "msg-race", ImConstants.CONVERSATION_GROUP, "cid-1", "staff-1",
				null, null, "TEXT", "query", true, null, Map.of("msgId", "msg-race"));

		callbackService.handleStreamMessage(connector(), message);

		verify(invocationService, never()).invokeDetailed(any());
	}

	@Test
	void thinkingMessageFailureDoesNotBlockFinalReply() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		List<AgentImMessage> insertedMessages = new ArrayList<>();
		List<String> sentMessages = new ArrayList<>();
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		doAnswer(invocation -> {
			AgentImMessage message = invocation.getArgument(0);
			insertedMessages.add(message);
			return 1;
		}).when(messageMapper).insert(any(AgentImMessage.class));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		when(adapter.responsePayload("ok")).thenReturn(Map.of("success", true));
		doAnswer(invocation -> {
			String text = invocation.getArgument(2);
			if ("正在思考中，请耐心等候...".equals(text)) {
				throw new RuntimeException("send-thinking-failed");
			}
			sentMessages.add(text);
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		AgentImUserIdentityMapper identityMapper = mock(AgentImUserIdentityMapper.class);
		AgentImUserIdentity identity = new AgentImUserIdentity();
		identity.setUserId("42");
		when(identityMapper.findEnabledByExternalUser("100", ImConstants.PROVIDER_DINGTALK,
				"dingtalk-customer-service", "staff-1"))
			.thenReturn(identity);

		DelegatedAuthContextService delegatedAuthContextService = mock(DelegatedAuthContextService.class);
		DelegatedAuthContextResp authContext = new DelegatedAuthContextResp();
		authContext.setUserId("42");
		authContext.setTenantId("100");
		authContext.setAccessToken("token");
		authContext.setDataPermission(new DataPermission());
		when(delegatedAuthContextService.issue(any())).thenReturn(DelegatedAuthContextService.IssueResult.ok(authContext));
		when(delegatedAuthContextService.executeWith(any(), any())).thenAnswer(invocation -> invocation
			.<java.util.function.Supplier<?>>getArgument(1)
			.get());

		DataChatSessionService chatSessionService = chatSessionService();
		ChannelSessionMappingService channelSessionMappingService = mock(ChannelSessionMappingService.class);
		AgentChannelSessionMapping mapping = AgentChannelSessionMapping.builder()
			.agentId(100L)
			.sessionId(900L)
			.build();
		when(channelSessionMappingService.getOrCreate(any())).thenReturn(mapping);

		AgentInvocationService agentInvocationService = mock(AgentInvocationService.class);
		when(agentInvocationService.invokeDetailed(any())).thenAnswer(invocation -> new AgentInvocationResult("ok", null));

		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), identityMapper, messageMapper, adapterRegistry, jsonSupport,
				objectMapper, channelSessionMappingService, chatSessionService, agentInvocationService,
				mock(DingTalkCredentialService.class), delegatedAuthContextService, runtimeConfigService());

		AgentImConnector connector = connector();
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"msg-fail", ImConstants.CONVERSATION_GROUP, "cid-1", "staff-1", null, null, "TEXT", "@bot query logs",
				true, "https://example.com/reply", Map.of("msgId", "msg-fail"));

		callbackService.handleStreamMessage(connector, message);

		assertEquals(1, sentMessages.size());
		assertEquals("ok", sentMessages.get(0));
		assertEquals(2, insertedMessages.size());
		assertEquals("ok", insertedMessages.get(0).getResponseContent());
	}

	@Test
	void webhookSecurityFailureKeepsInboundSuccessAndMarksOutboundFailed() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		List<AgentImMessage> insertedMessages = new ArrayList<>();
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		doAnswer(invocation -> {
			AgentImMessage message = invocation.getArgument(0);
			insertedMessages.add(message);
			return 1;
		}).when(messageMapper).insert(any(AgentImMessage.class));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		doAnswer(invocation -> {
			throw CheckedException.badRequest(480017, "IM 回复 Webhook 不安全或未在白名单中");
		}).when(adapter).sendReply(any(), any(), anyString());

		AgentImUserIdentityMapper identityMapper = mock(AgentImUserIdentityMapper.class);
		AgentImUserIdentity identity = new AgentImUserIdentity();
		identity.setUserId("42");
		when(identityMapper.findEnabledByExternalUser("100", ImConstants.PROVIDER_DINGTALK,
				"dingtalk-customer-service", "staff-1"))
			.thenReturn(identity);

		DelegatedAuthContextService delegatedAuthContextService = mock(DelegatedAuthContextService.class);
		DelegatedAuthContextResp authContext = new DelegatedAuthContextResp();
		authContext.setUserId("42");
		authContext.setTenantId("100");
		authContext.setAccessToken("token");
		authContext.setDataPermission(new DataPermission());
		when(delegatedAuthContextService.issue(any())).thenReturn(DelegatedAuthContextService.IssueResult.ok(authContext));
		when(delegatedAuthContextService.executeWith(any(), any())).thenAnswer(invocation -> invocation
			.<java.util.function.Supplier<?>>getArgument(1)
			.get());

		ChannelSessionMappingService channelSessionMappingService = mock(ChannelSessionMappingService.class);
		when(channelSessionMappingService.getOrCreate(any())).thenReturn(AgentChannelSessionMapping.builder()
			.agentId(100L)
			.sessionId(900L)
			.build());
		AgentInvocationService agentInvocationService = mock(AgentInvocationService.class);
		when(agentInvocationService.invokeDetailed(any())).thenReturn(new AgentInvocationResult("ok", null));

		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), identityMapper, messageMapper, adapterRegistry, jsonSupport,
				objectMapper, channelSessionMappingService, chatSessionService(), agentInvocationService,
				mock(DingTalkCredentialService.class), delegatedAuthContextService, runtimeConfigService());
		AgentImConnector connector = connector();
		connector.setEncryptedConfig(jsonSupport.writeEncryptedMap(Map.of("thinkingMessageEnabled", false)));
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"msg-webhook-fail", ImConstants.CONVERSATION_GROUP, "cid-1", "staff-1", null, null, "TEXT",
				"@bot query logs", true, "https://oapi.dingtalk.com/reply", Map.of("msgId", "msg-webhook-fail"));

		var response = callbackService.handleStreamMessage(connector, message);

		assertFalse(response.success());
		assertEquals(2, insertedMessages.size());
		AgentImMessage inbound = insertedMessages.get(0);
		AgentImMessage outbound = insertedMessages.get(1);
		assertEquals(ImConstants.MESSAGE_STATUS_SUCCESS, inbound.getStatus());
		assertEquals("ok", inbound.getResponseContent());
		assertNull(inbound.getErrorCode());
		assertEquals(ImConstants.MESSAGE_STATUS_FAILED, outbound.getStatus());
		assertEquals("480017", outbound.getErrorCode());
		verify(adapter, times(1)).sendReply(any(), any(), anyString());
		verify(agentInvocationService, times(1)).invokeDetailed(any());
	}

	@Test
	void approvalCommandIsInterceptedAndDoesNotInvokeAgent() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		List<AgentImMessage> insertedMessages = new ArrayList<>();
		List<String> sentMessages = new ArrayList<>();
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		doAnswer(invocation -> {
			insertedMessages.add(invocation.getArgument(0));
			return 1;
		}).when(messageMapper).insert(any(AgentImMessage.class));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		doAnswer(invocation -> {
			sentMessages.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		AgentImUserIdentityMapper identityMapper = mock(AgentImUserIdentityMapper.class);
		AgentImUserIdentity identity = new AgentImUserIdentity();
		identity.setUserId("42");
		when(identityMapper.findEnabledByExternalUser("100", ImConstants.PROVIDER_DINGTALK,
				"dingtalk-customer-service", "staff-1"))
			.thenReturn(identity);

		DelegatedAuthContextService delegatedAuthContextService = mock(DelegatedAuthContextService.class);
		DelegatedAuthContextResp authContext = new DelegatedAuthContextResp();
		authContext.setUserId("42");
		authContext.setTenantId("100");
		authContext.setAccessToken("token");
		authContext.setDataPermission(new DataPermission());
		when(delegatedAuthContextService.issue(any())).thenReturn(DelegatedAuthContextService.IssueResult.ok(authContext));
		when(delegatedAuthContextService.executeWith(any(), any())).thenAnswer(invocation -> invocation
			.<java.util.function.Supplier<?>>getArgument(1)
			.get());

		authContext.setFuncPermissions(List.of(ImConstants.PERMISSION_APPROVAL_REVIEW));

		AgentInvocationService agentInvocationService = mock(AgentInvocationService.class);
		com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService approvalService = mock(
				com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService.class);
		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), identityMapper, messageMapper, adapterRegistry, jsonSupport,
				objectMapper, mock(ChannelSessionMappingService.class), chatSessionService(), agentInvocationService,
				new FlowTextRenderer(), mock(DingTalkCredentialService.class), delegatedAuthContextService,
				new ImApprovalCommandService(approvalService), null, runtimeConfigService(), Runnable::run);

		AgentImConnector connector = connector();
		connector.setEncryptedConfig(jsonSupport.writeEncryptedMap(Map.of("thinkingMessageEnabled", false)));
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"msg-approval", ImConstants.CONVERSATION_GROUP, "cid-1", "staff-1", null, null, "TEXT",
				"@数字员工 同意 7", true, "https://oapi.dingtalk.com/reply", Map.of("msgId", "msg-approval"));

		var response = callbackService.handleStreamMessage(connector, message);

		assertTrue(response.success());
		verify(approvalService, times(1)).approve("100", "42", 7L, "IM 指令同意");
		verify(agentInvocationService, never()).invokeDetailed(any());
		assertEquals(1, sentMessages.size());
		assertEquals("已同意审批 #7。", sentMessages.get(0));
		assertEquals(1, insertedMessages.size());
		assertEquals(ImConstants.MESSAGE_STATUS_SUCCESS, insertedMessages.get(0).getStatus());
		assertEquals("已同意审批 #7。", insertedMessages.get(0).getResponseContent());
	}

	/**
	 * H-1 端到端：已完成 IM 绑定但不持有 ai-agent:approval:review 的成员，
	 * 发「同意 <ID>」不得批准任何审批，且入站消息落 FAILED 留痕。
	 */
	@Test
	void approvalCommandFromUserWithoutPermissionIsRefused() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		List<AgentImMessage> insertedMessages = new ArrayList<>();
		List<String> sentMessages = new ArrayList<>();
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		doAnswer(invocation -> {
			insertedMessages.add(invocation.getArgument(0));
			return 1;
		}).when(messageMapper).insert(any(AgentImMessage.class));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		doAnswer(invocation -> {
			sentMessages.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		AgentImUserIdentityMapper identityMapper = mock(AgentImUserIdentityMapper.class);
		AgentImUserIdentity identity = new AgentImUserIdentity();
		identity.setUserId("42");
		when(identityMapper.findEnabledByExternalUser("100", ImConstants.PROVIDER_DINGTALK,
				"dingtalk-customer-service", "staff-1"))
			.thenReturn(identity);

		DelegatedAuthContextService delegatedAuthContextService = mock(DelegatedAuthContextService.class);
		DelegatedAuthContextResp authContext = new DelegatedAuthContextResp();
		authContext.setUserId("42");
		authContext.setTenantId("100");
		authContext.setAccessToken("token");
		authContext.setDataPermission(new DataPermission());
		// 绑定成功但没有审批权限：这正是 H-1 的攻击者画像。
		authContext.setFuncPermissions(List.of("ai-agent:approval:query"));
		when(delegatedAuthContextService.issue(any())).thenReturn(DelegatedAuthContextService.IssueResult.ok(authContext));
		when(delegatedAuthContextService.executeWith(any(), any())).thenAnswer(invocation -> invocation
			.<java.util.function.Supplier<?>>getArgument(1)
			.get());

		AgentInvocationService agentInvocationService = mock(AgentInvocationService.class);
		com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService approvalService = mock(
				com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService.class);
		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), identityMapper, messageMapper, adapterRegistry, jsonSupport,
				objectMapper, mock(ChannelSessionMappingService.class), chatSessionService(), agentInvocationService,
				new FlowTextRenderer(), mock(DingTalkCredentialService.class), delegatedAuthContextService,
				new ImApprovalCommandService(approvalService), null, runtimeConfigService(), Runnable::run);

		AgentImConnector connector = connector();
		connector.setEncryptedConfig(jsonSupport.writeEncryptedMap(Map.of("thinkingMessageEnabled", false)));
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"msg-approval-denied", ImConstants.CONVERSATION_GROUP, "cid-1", "staff-1", null, null, "TEXT",
				"@数字员工 同意 7", true, "https://oapi.dingtalk.com/reply", Map.of("msgId", "msg-approval-denied"));

		callbackService.handleStreamMessage(connector, message);

		verify(approvalService, never()).approve(anyString(), anyString(), anyLong(), any());
		verify(approvalService, never()).reject(anyString(), anyString(), anyLong(), any());
		verify(agentInvocationService, never()).invokeDetailed(any());
		assertEquals(1, sentMessages.size());
		assertTrue(sentMessages.get(0).contains("您无权处理该审批"), sentMessages.get(0));
		assertEquals(ImConstants.MESSAGE_STATUS_FAILED, insertedMessages.get(0).getStatus());
		assertEquals(String.valueOf(ImErrorDict.APPROVAL_COMMAND_FAILED.getValue()),
				insertedMessages.get(0).getErrorCode());
	}

	/**
	 * H-5：回调路径只带 (provider, connectorCode)，同码连接器跨租户存在。
	 * 多个候选同时验签通过说明配了同码同密钥，静默择一会把消息投进错误租户，必须整体拒绝。
	 */
	@Test
	void multipleTenantsMatchingSameConnectorCodeAreRejectedInsteadOfPickingOne() {
		AgentImConnectorMapper connectorMapper = mock(AgentImConnectorMapper.class);
		AgentImConnector tenant100 = connector();
		AgentImConnector tenant200 = connector();
		tenant200.setTenantId("200");
		when(connectorMapper.findCallbackCandidates(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service"))
			.thenReturn(List.of(tenant100, tenant200));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);

		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		ImCallbackService callbackService = new ImCallbackService(connectorMapper,
				mock(AgentImConversationBindingMapper.class), mock(AgentImUserIdentityMapper.class), messageMapper,
				adapterRegistry, jsonSupport, objectMapper, mock(ChannelSessionMappingService.class),
				mock(DataChatSessionService.class), mock(AgentInvocationService.class),
				mock(DingTalkCredentialService.class), mock(DelegatedAuthContextService.class), runtimeConfigService());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> callbackService.handleCallback(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
						Map.of(), Map.of(), "{}"));

		assertEquals(ImErrorDict.CONNECTOR_CODE_AMBIGUOUS.getValue(), ex.getCode());
		// 归属租户未确定就不能占用防重放凭据，也不能解析任何业务字段。
		verify(adapter, never()).claimReplayGuard(anyString(), anyString(), any(), any(), anyString());
		verify(adapter, never()).parse(anyString(), anyString(), anyString());
		verify(messageMapper, never()).insert(any(AgentImMessage.class));
	}

	/** 唯一候选验签通过后才占用防重放凭据，且键用验签通过的连接器租户。 */
	@Test
	void uniqueVerifiedCandidateClaimsReplayGuardWithItsOwnTenant() {
		AgentImConnectorMapper connectorMapper = mock(AgentImConnectorMapper.class);
		when(connectorMapper.findCallbackCandidates(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service"))
			.thenReturn(List.of(connector()));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		when(adapter.parse(anyString(), anyString(), anyString())).thenReturn(new ImCallbackMessage(
				ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service", "msg-1", ImConstants.CONVERSATION_GROUP,
				"cid-1", "staff-1", null, null, "IMAGE", "img", true, null, Map.of()));

		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		ImCallbackService callbackService = new ImCallbackService(connectorMapper,
				mock(AgentImConversationBindingMapper.class), mock(AgentImUserIdentityMapper.class), messageMapper,
				adapterRegistry, jsonSupport, objectMapper, mock(ChannelSessionMappingService.class),
				mock(DataChatSessionService.class), mock(AgentInvocationService.class),
				mock(DingTalkCredentialService.class), mock(DelegatedAuthContextService.class), runtimeConfigService());

		callbackService.handleCallback(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service", Map.of(), Map.of(),
				"{}");

		verify(adapter).claimReplayGuard(eq("100"), eq("dingtalk-customer-service"), any(), any(), anyString());
	}

	@Test
	void findsStableToolFailureThroughWrappedInvocationException() {
		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), mock(AgentImUserIdentityMapper.class),
				mock(AgentImMessageMapper.class), mock(ImAdapterRegistry.class), jsonSupport, objectMapper,
				mock(ChannelSessionMappingService.class), mock(DataChatSessionService.class),
				mock(AgentInvocationService.class), mock(DingTalkCredentialService.class),
				mock(DelegatedAuthContextService.class), runtimeConfigService());
		ToolInvocationException expected = new ToolInvocationException(
				ToolInvocationException.Code.TOOL_PERMISSION_DENIED, "当前账号缺少工具权限");

		ToolInvocationException actual = ReflectionTestUtils.invokeMethod(callbackService,
				"findToolInvocationFailure", new IllegalStateException("wrapped", expected));

		assertSame(expected, actual);
	}

	@Test
	void bindCodeIsConsumedBeforeIdentityAndAgentLookup() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		doAnswer(invocation -> 1).when(messageMapper).insert(any(AgentImMessage.class));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);

		ImUserBindSessionService bindSessionService = mock(ImUserBindSessionService.class);
		when(bindSessionService.consume(any(), any())).thenReturn(
				ImUserBindSessionService.ConsumeResult.ok("1", "已绑定到账号 平台管理员，可以开始对话。"));

		DelegatedAuthContextService delegatedAuthContextService = mock(DelegatedAuthContextService.class);
		AgentImConversationBindingMapper bindingMapper = mock(AgentImConversationBindingMapper.class);
		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class), bindingMapper,
				mock(AgentImUserIdentityMapper.class), messageMapper, adapterRegistry, jsonSupport, objectMapper,
				mock(ChannelSessionMappingService.class), mock(DataChatSessionService.class),
				mock(AgentInvocationService.class), mock(DingTalkCredentialService.class), delegatedAuthContextService,
				runtimeConfigService(), bindSessionService);

		AgentImConnector connector = connector();
		connector.setDirectEnabled(false);
		connector.setDefaultAgentId(null);
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"msg-bind", ImConstants.CONVERSATION_SINGLE, "cid-1", "manager4081", null, null, "TEXT",
				"BIND-A2B3C4D5", false, "https://oapi.dingtalk.com/robot/sendBySession?session=x", Map.of());

		callbackService.handleStreamMessage(connector, message);

		verify(bindSessionService).consume(eq(connector), eq(message));
		verify(delegatedAuthContextService, never()).issue(any());
		verify(bindingMapper, never()).findEnabled(any(), any(), any(), any(), any());
		verify(adapter).sendReply(any(), eq(message), eq("已绑定到账号 平台管理员，可以开始对话。"));
	}

	@Test
	void plainTextDoesNotUseBindSessionServiceSuccessPath() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		doAnswer(invocation -> 1).when(messageMapper).insert(any(AgentImMessage.class));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);

		ImUserBindSessionService bindSessionService = mock(ImUserBindSessionService.class);
		when(bindSessionService.consume(any(), any())).thenReturn(ImUserBindSessionService.ConsumeResult.ignored());

		AgentImUserIdentityMapper identityMapper = mock(AgentImUserIdentityMapper.class);
		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), identityMapper, messageMapper, adapterRegistry,
				jsonSupport, objectMapper, mock(ChannelSessionMappingService.class), mock(DataChatSessionService.class),
				mock(AgentInvocationService.class), mock(DingTalkCredentialService.class),
				mock(DelegatedAuthContextService.class), runtimeConfigService(), bindSessionService);

		AgentImConnector connector = connector();
		connector.setDirectEnabled(true);
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"msg-hi", ImConstants.CONVERSATION_SINGLE, "cid-1", "manager4081", null, null, "TEXT", "你好", false,
				"https://oapi.dingtalk.com/robot/sendBySession?session=x", Map.of());
		callbackService.handleStreamMessage(connector, message);

		verify(bindSessionService).consume(eq(connector), eq(message));
		verify(identityMapper).findEnabledByExternalUser("100", ImConstants.PROVIDER_DINGTALK,
				"dingtalk-customer-service", "manager4081");
	}

	@Test
	void missingIdentityRepliesUnboundAndDoesNotInvokeAgent() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		List<String> sentMessages = new ArrayList<>();
		List<AgentImMessage> inboundUpdates = new ArrayList<>();
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		doAnswer(invocation -> 1).when(messageMapper).insert(any(AgentImMessage.class));
		doAnswer(invocation -> {
			inboundUpdates.add(invocation.getArgument(0));
			return 1;
		}).when(messageMapper).updateById(any(AgentImMessage.class));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		doAnswer(invocation -> {
			sentMessages.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		AgentImUserIdentityMapper identityMapper = mock(AgentImUserIdentityMapper.class);
		when(identityMapper.findEnabledByExternalUser(any(), any(), any(), any())).thenReturn(null);
		DingTalkCredentialService credentialService = mock(DingTalkCredentialService.class);
		when(credentialService.resolveUserContact(any(), any())).thenReturn(null);
		DelegatedAuthContextService delegatedAuthContextService = mock(DelegatedAuthContextService.class);
		AgentInvocationService invocationService = mock(AgentInvocationService.class);

		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), identityMapper, messageMapper, adapterRegistry, jsonSupport,
				objectMapper, mock(ChannelSessionMappingService.class), chatSessionService(), invocationService,
				credentialService, delegatedAuthContextService, runtimeConfigService());

		AgentImConnector connector = connector();
		connector.setDirectEnabled(true);
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"msg-unbound", ImConstants.CONVERSATION_SINGLE, "cid-1", "staff-1", null, null, "TEXT", "你能干啥", false,
				"https://oapi.dingtalk.com/robot/sendBySession?session=x", Map.of());

		callbackService.handleStreamMessage(connector, message);

		assertEquals(1, sentMessages.size());
		assertEquals("未识别到您的系统账号，请先联系管理员完成 IM 用户绑定。", sentMessages.get(0));
		verify(delegatedAuthContextService, never()).issue(any());
		verify(invocationService, never()).invokeDetailed(any());
		assertEquals(String.valueOf(ImErrorDict.USER_NOT_BOUND.getValue()), inboundUpdates.get(0).getErrorCode());
	}

	@Test
	void boundIdentityWithIamFailureRepliesRetryNotBind() {
		AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);
		List<String> sentMessages = new ArrayList<>();
		List<AgentImMessage> inboundUpdates = new ArrayList<>();
		when(messageMapper.findByIdempotencyKey(any())).thenReturn(null);
		doAnswer(invocation -> 1).when(messageMapper).insert(any(AgentImMessage.class));
		doAnswer(invocation -> {
			inboundUpdates.add(invocation.getArgument(0));
			return 1;
		}).when(messageMapper).updateById(any(AgentImMessage.class));

		ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);
		ImAdapter adapter = mock(ImAdapter.class);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		doAnswer(invocation -> {
			sentMessages.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		AgentImUserIdentityMapper identityMapper = mock(AgentImUserIdentityMapper.class);
		AgentImUserIdentity identity = new AgentImUserIdentity();
		identity.setUserId("42");
		when(identityMapper.findEnabledByExternalUser("100", ImConstants.PROVIDER_DINGTALK,
				"dingtalk-customer-service", "staff-1"))
			.thenReturn(identity);

		DelegatedAuthContextService delegatedAuthContextService = mock(DelegatedAuthContextService.class);
		when(delegatedAuthContextService.issue(any())).thenReturn(DelegatedAuthContextService.IssueResult.timeout());
		AgentInvocationService invocationService = mock(AgentInvocationService.class);

		ImCallbackService callbackService = new ImCallbackService(mock(AgentImConnectorMapper.class),
				mock(AgentImConversationBindingMapper.class), identityMapper, messageMapper, adapterRegistry, jsonSupport,
				objectMapper, mock(ChannelSessionMappingService.class), chatSessionService(), invocationService,
				mock(DingTalkCredentialService.class), delegatedAuthContextService, runtimeConfigService());

		AgentImConnector connector = connector();
		connector.setDirectEnabled(true);
		ImCallbackMessage message = new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"msg-iam-timeout", ImConstants.CONVERSATION_SINGLE, "cid-1", "staff-1", null, null, "TEXT", "你能干啥",
				false, "https://oapi.dingtalk.com/robot/sendBySession?session=x", Map.of());

		callbackService.handleStreamMessage(connector, message);

		assertEquals(1, sentMessages.size());
		assertEquals("暂时无法确认您的登录身份，请稍后重试。", sentMessages.get(0));
		verify(invocationService, never()).invokeDetailed(any());
		assertEquals(String.valueOf(ImErrorDict.AUTH_SNAPSHOT_EMPTY.getValue()), inboundUpdates.get(0).getErrorCode());
	}

	private AgentImConnector connector() {
		AgentImConnector connector = new AgentImConnector();
		// 回调链路租户以验签通过的连接器为准，缺租户会被 processInbound 失败关闭。
		connector.setTenantId("100");
		connector.setProvider(ImConstants.PROVIDER_DINGTALK);
		connector.setConnectorCode("dingtalk-customer-service");
		connector.setEncryptedConfig(jsonSupport.writeEncryptedMap(Map.of()));
		connector.setDefaultAgentId(100L);
		connector.setGroupEnabled(true);
		connector.setStatus(ImConstants.STATUS_ENABLED);
		return connector;
	}

	private DataChatSessionService chatSessionService() {
		return mock(DataChatSessionService.class);
	}

	private ImRuntimeConfigService runtimeConfigService() {
		ImRuntimeConfigService runtimeConfigService = mock(ImRuntimeConfigService.class);
		when(runtimeConfigService.providerRuntimeConfig(ImConstants.PROVIDER_DINGTALK))
			.thenReturn(defaultRuntimeConfig());
		return runtimeConfigService;
	}

	private ImRuntimeConfigService.ProviderRuntimeConfig defaultRuntimeConfig() {
		return new ImRuntimeConfigService.ProviderRuntimeConfig(ImConstants.PROVIDER_DINGTALK,
				"https://open-dev.dingtalk.com", 30, 5000, 5000, 5000, true, 90, 30, true, 5000,
				"https://api.dingtalk.com/v1.0/oauth2/accessToken",
				"https://api.dingtalk.com/v1.0/contact/users/{userId}", 5000, 5000, 900L, "pc-web",
				"delegated-agent", true, ImConstants.TRIGGER_ALWAYS, ImConstants.TRIGGER_MENTION,
				"未识别到您的系统账号，请先联系管理员完成 IM 用户绑定。", "暂时无法确认您的登录身份，请稍后重试。",
				"当前 IM 对话连接器未启用。", "暂时只支持文本消息。", true,
				"正在思考中，请耐心等候...", "Agent 执行失败，请稍后再试。",
				"本次分析超时，请缩小查询范围或补充筛选条件后重试；如果已触发后台任务，请稍后查看结果。");
	}

}
