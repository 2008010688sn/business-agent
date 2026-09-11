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
import com.sn68.agent.dataagent.im.adapter.ImAdapter;
import com.sn68.agent.dataagent.im.adapter.ImAdapterRegistry;
import com.sn68.agent.dataagent.im.dto.ImCallbackMessage;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.entity.AgentImConversationBinding;
import com.sn68.agent.dataagent.im.entity.AgentImMessage;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.repository.AgentImConnectorMapper;
import com.sn68.agent.dataagent.im.repository.AgentImConversationBindingMapper;
import com.sn68.agent.dataagent.im.repository.AgentImMessageMapper;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent;
import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时事件 → IM 推送映射：事件过滤、绑定定位、文案渲染、幂等与失败重试语义。
 */
class ImRuntimeEventNotifierTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final NotificationJsonSupport jsonSupport = new NotificationJsonSupport(objectMapper,
			new SensitiveConfigCryptoService(cryptoDisabledProperties()));

	private final AgentImConversationBindingMapper bindingMapper = mock(AgentImConversationBindingMapper.class);

	private final AgentImConnectorMapper connectorMapper = mock(AgentImConnectorMapper.class);

	private final AgentImMessageMapper messageMapper = mock(AgentImMessageMapper.class);

	private final ImAdapterRegistry adapterRegistry = mock(ImAdapterRegistry.class);

	private final ImAdapter adapter = mock(ImAdapter.class);

	private final ImRuntimeEventNotifier notifier = new ImRuntimeEventNotifier(bindingMapper, connectorMapper,
			messageMapper, adapterRegistry, jsonSupport, objectMapper);

	private static DataAgentProperties cryptoDisabledProperties() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getCrypto().setEnabled(false);
		return properties;
	}

	/**
	 * H-1：审批卡片会推给租户下全部启用会话，群成员未必持有审批权限且审批ID自增可枚举，
	 * 因此群聊只给摘要、不给可执行指令格式。
	 */
	@Test
	void approvalRequestedEventPushedToGroupOmitsExecutableCommand() {
		List<AgentImMessage> inserted = wireHappyPath();
		List<String> sentTexts = new ArrayList<>();
		ArgumentCaptor<ImCallbackMessage> messageCaptor = ArgumentCaptor.forClass(ImCallbackMessage.class);
		doAnswer(invocation -> {
			sentTexts.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), messageCaptor.capture(), anyString());

		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", 5L, "9001", "APPROVAL_REQUESTED", "evt-1",
				"{\"approvalId\":55,\"capabilityCode\":\"demo.echo.close\",\"riskLevel\":\"HIGH\"}"));

		assertEquals(1, sentTexts.size());
		String text = sentTexts.get(0);
		assertTrue(text.contains("审批请求"));
		assertTrue(text.contains("#55"));
		assertTrue(text.contains("demo.echo.close"));
		assertFalse(text.contains("同意 55"), text);
		assertFalse(text.contains("拒绝 55"), text);
		assertTrue(text.contains("控制台"), text);
		assertEquals("cid-1", messageCaptor.getValue().externalConversationId());
		assertEquals(1, inserted.size());
		AgentImMessage outbound = inserted.get(0);
		assertEquals("100:DINGTALK:conn:RUNTIME:evt-1:cid-1", outbound.getIdempotencyKey());
		assertEquals(ImConstants.DIRECTION_OUTBOUND, outbound.getDirection());
		assertEquals(ImConstants.MESSAGE_STATUS_SUCCESS, outbound.getStatus());
		assertEquals("evt-1", outbound.getRuntimeRequestId());
	}

	/** 单聊仍保留一期简易指令协议：会话双方就是绑定用户本人，权限由 ImApprovalCommandService 判定。 */
	@Test
	void approvalRequestedEventPushedToSingleChatKeepsCommandHint() {
		wireHappyPath(ImConstants.CONVERSATION_SINGLE);
		List<String> sentTexts = new ArrayList<>();
		doAnswer(invocation -> {
			sentTexts.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", 5L, "9001", "APPROVAL_REQUESTED", "evt-single",
				"{\"approvalId\":55,\"capabilityCode\":\"demo.echo.close\"}"));

		assertEquals(1, sentTexts.size());
		assertTrue(sentTexts.get(0).contains("同意 55"), sentTexts.get(0));
		assertTrue(sentTexts.get(0).contains("拒绝 55"), sentTexts.get(0));
	}

	/**
	 * 会话类型白名单：绑定管理接口不校验取值枚举，未知类型（新平台的频道/话题、脏数据）
	 * 必须按多人会话处理，不能因为「不等于 GROUP」就把可执行审批指令推出去。
	 */
	@Test
	void approvalRequestedEventPushedToUnknownConversationTypeOmitsExecutableCommand() {
		wireHappyPath("CHANNEL");
		List<String> sentTexts = new ArrayList<>();
		doAnswer(invocation -> {
			sentTexts.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", 5L, "9001", "APPROVAL_REQUESTED", "evt-channel",
				"{\"approvalId\":55,\"capabilityCode\":\"demo.echo.close\"}"));

		assertEquals(1, sentTexts.size());
		assertFalse(sentTexts.get(0).contains("同意 55"), sentTexts.get(0));
		assertFalse(sentTexts.get(0).contains("拒绝 55"), sentTexts.get(0));
		assertTrue(sentTexts.get(0).contains("控制台"), sentTexts.get(0));
	}

	/** R2：取消与超时同属 RuntimeOutboxService 写入的运行终态事件，漏收会让 IM 侧静默无通知。 */
	@Test
	void cancelledAndTimedOutRunsAreNotified() {
		wireHappyPath();
		List<String> sentTexts = new ArrayList<>();
		doAnswer(invocation -> {
			sentTexts.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", 5L, "9001", "RUN_CANCELLED", "evt-cancel", "{}"));
		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", 5L, "9001", "RUN_TIMED_OUT", "evt-timeout", "{}"));

		assertEquals(2, sentTexts.size());
		assertTrue(sentTexts.get(0).contains("CANCELLED"), sentTexts.get(0));
		assertTrue(sentTexts.get(1).contains("TIMED_OUT"), sentTexts.get(1));
	}

	/** R3：连接器缺失是配置事故，不能只 debug 一行还计为投递成功，必须留 FAILED 出站消息可查可补发。 */
	@Test
	void missingConnectorIsRecordedAsFailedInsteadOfSilentlySkipped() {
		List<AgentImMessage> inserted = wireHappyPath();
		when(connectorMapper.findEnabledByTenantAndConnectorCode("100", "conn")).thenReturn(null);

		assertDoesNotThrow(() -> notifier.onRuntimeOutboxEvent(
				new RuntimeOutboxEvent("100", 5L, "9001", "APPROVAL_REQUESTED", "evt-noconn", "{\"approvalId\":88}")));

		assertEquals(1, inserted.size());
		assertEquals(ImConstants.MESSAGE_STATUS_FAILED, inserted.get(0).getStatus());
		assertEquals("100:DINGTALK:conn:RUNTIME:evt-noconn:cid-1", inserted.get(0).getIdempotencyKey());
		verify(adapter, never()).sendReply(any(), any(), anyString());
	}

	@Test
	void missingAdapterIsRecordedAsFailedInsteadOfSilentlySkipped() {
		List<AgentImMessage> inserted = wireHappyPath();
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenThrow(new IllegalStateException("no adapter"));

		assertDoesNotThrow(() -> notifier.onRuntimeOutboxEvent(
				new RuntimeOutboxEvent("100", 5L, "9001", "RUN_FAILED", "evt-noadapter", "{}")));

		assertEquals(1, inserted.size());
		assertEquals(ImConstants.MESSAGE_STATUS_FAILED, inserted.get(0).getStatus());
	}

	@Test
	void runFailedAndClarifyEventsRenderDedicatedTexts() {
		wireHappyPath();
		List<String> sentTexts = new ArrayList<>();
		doAnswer(invocation -> {
			sentTexts.add(invocation.getArgument(2));
			return null;
		}).when(adapter).sendReply(any(), any(), anyString());

		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", 5L, "9001", "RUN_FAILED", "evt-2",
				"{\"summary\":\"步骤执行超时\"}"));
		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", 5L, "9001", "CLARIFY_REQUESTED", "evt-3",
				"{\"question\":\"请确认查询的时间范围\"}"));

		assertEquals(2, sentTexts.size());
		assertTrue(sentTexts.get(0).contains("任务运行完成"));
		assertTrue(sentTexts.get(0).contains("FAILED"));
		assertTrue(sentTexts.get(0).contains("步骤执行超时"));
		assertTrue(sentTexts.get(1).contains("需要补充信息"));
		assertTrue(sentTexts.get(1).contains("请确认查询的时间范围"));
	}

	@Test
	void missingBindingOrUnsupportedTypeIsSkippedSilently() {
		when(bindingMapper.findEnabledByTenant("100")).thenReturn(List.of());

		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", 5L, "9001", "APPROVAL_REQUESTED", "evt-4", "{}"));
		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", 5L, "9001", "STEP_STARTED", "evt-5", "{}"));
		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", null, "9001", "APPROVAL_REQUESTED", "evt-6", "{}"));

		verify(adapter, never()).sendReply(any(), any(), anyString());
		verify(messageMapper, never()).insert(any(AgentImMessage.class));
	}

	@Test
	void deliveredEventKeyIsNotPushedAgainOnRedelivery() {
		wireHappyPath();
		AgentImMessage delivered = AgentImMessage.builder()
			.idempotencyKey("100:DINGTALK:conn:RUNTIME:evt-7:cid-1")
			.status(ImConstants.MESSAGE_STATUS_SUCCESS)
			.build();
		when(messageMapper.findByIdempotencyKey("100:DINGTALK:conn:RUNTIME:evt-7:cid-1")).thenReturn(delivered);

		notifier.onRuntimeOutboxEvent(new RuntimeOutboxEvent("100", 5L, "9001", "APPROVAL_REQUESTED", "evt-7",
				"{\"approvalId\":66}"));

		verify(adapter, never()).sendReply(any(), any(), anyString());
		verify(messageMapper, never()).insert(any(AgentImMessage.class));
	}

	/**
	 * 推送失败必须留痕但不外抛：同一事件上还有任务续发等主链路监听方，抛出会中断后续监听方。
	 */
	@Test
	void sendFailureMarksOutboundFailedWithoutBreakingOtherListeners() {
		List<AgentImMessage> inserted = wireHappyPath();
		doThrow(new RuntimeException("network-error")).when(adapter).sendReply(any(), any(), anyString());

		assertDoesNotThrow(() -> notifier.onRuntimeOutboxEvent(
				new RuntimeOutboxEvent("100", 5L, "9001", "APPROVAL_REQUESTED", "evt-8", "{\"approvalId\":77}")));

		assertEquals(1, inserted.size());
		assertEquals(ImConstants.MESSAGE_STATUS_FAILED, inserted.get(0).getStatus());
	}

	/**
	 * 装配一条启用绑定 + 启用连接器的正常链路，返回捕获的出站落库消息列表。
	 */
	private List<AgentImMessage> wireHappyPath() {
		return wireHappyPath(ImConstants.CONVERSATION_GROUP);
	}

	private List<AgentImMessage> wireHappyPath(String conversationType) {
		AgentImConversationBinding binding = AgentImConversationBinding.builder()
			.tenantId("100")
			.provider(ImConstants.PROVIDER_DINGTALK)
			.connectorCode("conn")
			.conversationType(conversationType)
			.externalConversationId("cid-1")
			.agentId(100L)
			.status(ImConstants.STATUS_ENABLED)
			.build();
		when(bindingMapper.findEnabledByTenant("100")).thenReturn(List.of(binding));
		AgentImConnector connector = new AgentImConnector();
		connector.setTenantId("100");
		connector.setProvider(ImConstants.PROVIDER_DINGTALK);
		connector.setConnectorCode("conn");
		connector.setStatus(ImConstants.STATUS_ENABLED);
		connector.setEncryptedConfig(jsonSupport.writeEncryptedMap(Map.of()));
		when(connectorMapper.findEnabledByTenantAndConnectorCode("100", "conn")).thenReturn(connector);
		when(adapterRegistry.get(ImConstants.PROVIDER_DINGTALK)).thenReturn(adapter);
		List<AgentImMessage> inserted = new ArrayList<>();
		when(messageMapper.findByIdempotencyKey(anyString())).thenReturn(null);
		doAnswer(invocation -> {
			inserted.add(invocation.getArgument(0));
			return 1;
		}).when(messageMapper).insert(any(AgentImMessage.class));
		return inserted;
	}

}
