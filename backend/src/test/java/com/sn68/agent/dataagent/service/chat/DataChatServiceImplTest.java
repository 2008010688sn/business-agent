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
package com.sn68.agent.dataagent.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.runtime.ContextCompressionService;
import com.sn68.agent.dataagent.agentscope.service.AgentScopeModelFactory;
import com.sn68.agent.dataagent.agentscope.session.AgentRuntimeRegistry;
import com.sn68.agent.dataagent.agentscope.session.AgentScopeNativeSessionService;
import com.sn68.agent.dataagent.agentscope.v2.V2AgentStateStore;
import com.sn68.agent.dataagent.dto.ChatMessageReq;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.chat.ChatSessionPageQueryReq;
import com.sn68.agent.dataagent.dto.chat.CreateChatSessionReq;
import com.sn68.agent.dataagent.dto.chat.SessionCallChainResp;
import com.sn68.agent.dataagent.dto.chat.SessionContextCompressionReq;
import com.sn68.agent.dataagent.dto.chat.SessionContextUsageReq;
import com.sn68.agent.dataagent.enums.ChatSessionChannelDict;
import com.sn68.agent.dataagent.enums.SessionContextCompressionStatus;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.AgentOrchestrationStep;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolExecutionRecord;
import com.sn68.agent.dataagent.observability.SessionTraceStore;
import com.sn68.agent.dataagent.repository.AgentOrchestrationRunMapper;
import com.sn68.agent.dataagent.repository.AgentOrchestrationStepMapper;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.service.agent.AgentModelConfigService;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.file.FilePreviewResp;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import com.sn68.agent.dataagent.service.report.AnalysisReportService;
import com.sn68.agent.dataagent.util.ReportTemplateUtil;
import com.sn68.agent.dataagent.vo.SessionContextCompressionVO;
import com.sn68.agent.dataagent.vo.SessionContextUsageVO;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataChatServiceImplTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void getSessionMessages_refreshesAttachmentPreviewUrlsFromSuitePath() throws Exception {
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		LocalFileService localFileService = mock(LocalFileService.class);
		DataChatMessage message = DataChatMessage.builder()
			.id(1L)
			.sessionId(100L)
			.role("user")
			.content("look")
			.messageType("text")
			.metadata("""
					{"attachments":[{"type":"image","storageKey":"https://bucket.oss-cn.example.com/2026/06/a.png","url":"https://expired.example.com/a.png","previewUrl":"https://expired.example.com/a.png","data":"base64","contentType":"image/png","size":12}]}
					""")
			.build();
		when(chatMessageService.findVisibleBySessionId(100L, 1L)).thenReturn(List.of(message));
		when(localFileService.findDisplayPreviews(anyCollection())).thenReturn(Map.of("https://bucket.oss-cn.example.com/2026/06/a.png",
				FilePreviewResp.builder()
					.path("https://bucket.oss-cn.example.com/2026/06/a.png")
					.originalName("a.png")
					.previewUrl("https://preview.example.com/signed/a.png")
					.build()));
		DataChatServiceImpl service = service(chatMessageService, localFileService);

		List<DataChatMessage> messages = service.getSessionMessages(100L, 1L);

		JsonNode attachment = objectMapper.readTree(messages.get(0).getMetadata()).path("attachments").get(0);
		assertEquals("https://preview.example.com/signed/a.png", attachment.path("url").asText());
		assertEquals("https://preview.example.com/signed/a.png", attachment.path("previewUrl").asText());
		assertEquals("https://bucket.oss-cn.example.com/2026/06/a.png", attachment.path("storageKey").asText());
		assertFalse(attachment.has("data"));
	}

	@Test
	void getSessionMessages_removesExpiredPreviewUrlWhenSuitePreviewUnavailable() throws Exception {
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		LocalFileService localFileService = mock(LocalFileService.class);
		DataChatMessage message = DataChatMessage.builder()
			.id(1L)
			.sessionId(100L)
			.metadata("""
					{"attachments":[{"type":"image","storageKey":"https://bucket.oss-cn.example.com/2026/06/a.png","url":"https://expired.example.com/a.png","previewUrl":"https://expired.example.com/a.png","data":"base64"}]}
					""")
			.build();
		when(chatMessageService.findVisibleBySessionId(100L, 1L)).thenReturn(List.of(message));
		when(localFileService.findDisplayPreviews(anyCollection())).thenReturn(Map.of());
		DataChatServiceImpl service = service(chatMessageService, localFileService);

		List<DataChatMessage> messages = service.getSessionMessages(100L, 1L);

		JsonNode attachment = objectMapper.readTree(messages.get(0).getMetadata()).path("attachments").get(0);
		assertTrue(attachment.has("storageKey"));
		assertFalse(attachment.has("url"));
		assertFalse(attachment.has("previewUrl"));
		assertFalse(attachment.has("data"));
	}

	@Test
	void getSessionContextUsageReturnsVisibleMessageEstimate() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		AgentModelConfigService agentModelConfigService = mock(AgentModelConfigService.class);
		when(sessionService.requireSessionForAgent(100L, 1L))
			.thenReturn(DataChatSession.builder().agentId(1L).build());
		ModelConfigDTO modelConfig = ModelConfigDTO.builder()
			.id(9L)
			.modelName("qwen3.7-plus-2026-05-26")
			.contextWindowTokens(32768L)
			.build();
		when(agentModelConfigService.resolveChatModelConfig(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(9L)))
			.thenReturn(modelConfig);
		when(chatMessageService.findVisibleBySessionId(100L, 1L))
			.thenReturn(List.of(
					DataChatMessage.builder().role("user").messageType("text").content("你好").build(),
					DataChatMessage.builder().role("assistant").messageType("text").content("你好，请问需要分析什么？").build()));
		DataChatServiceImpl service = service(sessionService, chatMessageService, mock(LocalFileService.class),
				sessionTraceStore(), mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class), agentModelConfigService);
		SessionContextUsageReq request = new SessionContextUsageReq();
		request.setAgentId(1L);
		request.setChatModelConfigId(9L);

		SessionContextUsageVO usage = service.getSessionContextUsage(100L, request);

		assertTrue(usage.getUsedTokens() > 0);
		assertEquals(32768L, usage.getLimitTokens());
		assertTrue(usage.getUsageRatio() > 0D);
		assertEquals(2, usage.getMessageCount());
		assertEquals("qwen3.7-plus-2026-05-26", usage.getModelName());
		assertEquals(9L, usage.getChatModelConfigId());
		assertTrue(usage.getEstimated());
	}

	@Test
	@SuppressWarnings("unchecked")
	void getSessionContextUsagePrefersAs2MessageTokens() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		AgentModelConfigService agentModelConfigService = mock(AgentModelConfigService.class);
		ContextCompressionService compressionService = mock(ContextCompressionService.class);
		when(sessionService.requireSessionForAgent(100L, 1L)).thenReturn(as2Session());
		ModelConfigDTO modelConfig = ModelConfigDTO.builder()
			.id(9L)
			.modelName("qwen3.7-plus-2026-05-26")
			.contextWindowTokens(32768L)
			.build();
		when(agentModelConfigService.resolveChatModelConfig(any(), eq(9L))).thenReturn(modelConfig);
		when(compressionService.countTokens(any())).thenReturn(128L);
		V2AgentStateStore store = mock(V2AgentStateStore.class);
		when(store.bind(any())).thenReturn(store);
		when(store.getList(eq("7"), eq("100"), eq("memory_messages"), eq(Msg.class)))
			.thenReturn(List.of(msg(MsgRole.USER, "hello"), msg(MsgRole.ASSISTANT, "world")));
		ObjectProvider<V2AgentStateStore> storeProvider = mock(ObjectProvider.class);
		when(storeProvider.getIfAvailable()).thenReturn(store);
		DataChatServiceImpl service = serviceWithAs2(sessionService, chatMessageService, agentModelConfigService,
				compressionService, storeProvider, mock(AgentScopeNativeSessionService.class));
		SessionContextUsageReq request = new SessionContextUsageReq();
		request.setAgentId(1L);
		request.setChatModelConfigId(9L);

		SessionContextUsageVO usage = service.getSessionContextUsage(100L, request);

		assertEquals(128L, usage.getUsedTokens());
		assertEquals(2, usage.getMessageCount());
		assertFalse(usage.getEstimated());
		verify(chatMessageService, never()).findVisibleBySessionId(100L, 1L);
	}

	@Test
	@SuppressWarnings("unchecked")
	void compressSessionContextWritesAs2AndKeepsFirstSystem() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		when(sessionService.requireSessionForAgent(100L, 1L)).thenReturn(as2Session());
		ContextCompressionService compressionService = mock(ContextCompressionService.class);
		when(compressionService.isManualContextEnabled()).thenReturn(true);
		when(compressionService.countTokens(any())).thenReturn(90L);
		Msg system = msg(MsgRole.SYSTEM, "P0 rules");
		Msg user = msg(MsgRole.USER, "hello");
		Msg assistant = msg(MsgRole.ASSISTANT, "world");
		V2AgentStateStore store = mock(V2AgentStateStore.class);
		when(store.bind(any())).thenReturn(store);
		when(store.getList(eq("7"), eq("100"), eq("memory_messages"), eq(Msg.class)))
			.thenReturn(List.of(system, user, assistant));
		InMemoryMemory compressed = new InMemoryMemory();
		compressed.addMessage(user);
		compressed.addMessage(assistant);
		when(compressionService.compressMemoryManually(any(), any(), any()))
			.thenReturn(new ContextCompressionService.CompressionResult(SessionContextCompressionStatus.COMPRESSED,
					true, 90L, 40L, 3, 2, "已压缩上下文", compressed));
		ObjectProvider<V2AgentStateStore> storeProvider = mock(ObjectProvider.class);
		when(storeProvider.getIfAvailable()).thenReturn(store);
		AgentScopeNativeSessionService nativeSessionService = mock(AgentScopeNativeSessionService.class);
		DataChatServiceImpl service = serviceWithAs2(sessionService, mock(ChatMessageService.class),
				mock(AgentModelConfigService.class), compressionService, storeProvider, nativeSessionService);
		SessionContextCompressionReq request = new SessionContextCompressionReq();
		request.setAgentId(1L);

		SessionContextCompressionVO result = service.compressSessionContext(100L, request);

		assertEquals(SessionContextCompressionStatus.COMPRESSED, result.getStatus());
		assertTrue(result.getCompressed());
		ArgumentCaptor<List<State>> saved = ArgumentCaptor.forClass(List.class);
		verify(store).save(eq("7"), eq("100"), eq("memory_messages"), saved.capture());
		assertEquals(MsgRole.SYSTEM, ((Msg) saved.getValue().get(0)).getRole());
		assertEquals("P0 rules", ((Msg) saved.getValue().get(0)).getTextContent());
		verifyNoInteractions(nativeSessionService);
	}

	@Test
	@SuppressWarnings("unchecked")
	void compressSessionContextReturnsNoCompressibleWhenAs2Empty() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		when(sessionService.requireSessionForAgent(100L, 1L)).thenReturn(as2Session());
		ContextCompressionService compressionService = mock(ContextCompressionService.class);
		when(compressionService.isManualContextEnabled()).thenReturn(true);
		V2AgentStateStore store = mock(V2AgentStateStore.class);
		when(store.bind(any())).thenReturn(store);
		when(store.getList(eq("7"), eq("100"), eq("memory_messages"), eq(Msg.class))).thenReturn(List.of());
		ObjectProvider<V2AgentStateStore> storeProvider = mock(ObjectProvider.class);
		when(storeProvider.getIfAvailable()).thenReturn(store);
		DataChatServiceImpl service = serviceWithAs2(sessionService, mock(ChatMessageService.class),
				mock(AgentModelConfigService.class), compressionService, storeProvider,
				mock(AgentScopeNativeSessionService.class));
		SessionContextCompressionReq request = new SessionContextCompressionReq();
		request.setAgentId(1L);

		SessionContextCompressionVO result = service.compressSessionContext(100L, request);

		assertEquals(SessionContextCompressionStatus.NO_COMPRESSIBLE_CONTENT, result.getStatus());
		assertFalse(result.getCompressed());
		verify(compressionService, never()).compressMemoryManually(any(), any(), any());
		verify(store, never()).save(any(), any(), any(), anyList());
	}

	@Test
	void getSessionCallChainMergesAnswerExplainToolStepsAndOrchestrationTrace() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		when(sessionService.requireSessionForAgent(100L, 1L))
			.thenReturn(DataChatSession.builder().agentId(1L).build());
		SessionTraceStore sessionTraceStore = mock(SessionTraceStore.class);
		when(sessionTraceStore.getTrace("100", "runtime-1")).thenReturn(Optional.empty());
		AnswerTraceExplainStore answerTraceExplainStore = new AnswerTraceExplainStore();
		AgentOrchestrationRunMapper runMapper = mock(AgentOrchestrationRunMapper.class);
		AgentOrchestrationStepMapper stepMapper = mock(AgentOrchestrationStepMapper.class);
		AgentOrchestrationRun run = AgentOrchestrationRun.builder().agentId(1L).runtimeRequestId("runtime-1").build();
		run.setId(10L);
		AgentOrchestrationStep step = AgentOrchestrationStep.builder().runId(10L).stepNo(1).toolCount(1).build();
		when(runMapper.findByRuntimeRequestIdAndAgentId("runtime-1", 1L)).thenReturn(run);
		when(stepMapper.findByRunId(10L)).thenReturn(List.of(step));
		answerTraceExplainStore.recordToolExecution(
				com.sn68.agent.dataagent.agentscope.dto.AgentRequest.builder()
					.agentId("1")
					.threadId("100")
					.runtimeRequestId("runtime-1")
					.build(),
				new ToolExecutionRecord("demo.tool", "success", 1000L, 1100L, 100L, "{}", "{\"ok\":true}",
						null, null, "工具执行成功，耗时 100ms", "{\"ok\":true}"));
		DataChatServiceImpl service = service(sessionService, mock(ChatMessageService.class), mock(LocalFileService.class),
				sessionTraceStore, answerTraceExplainStore, runMapper, stepMapper);

		SessionCallChainResp callChain = service.getSessionCallChain(100L, 1L, "runtime-1");

		assertEquals("runtime-1", callChain.getRuntimeRequestId());
		assertEquals(1, callChain.getToolSteps().size());
		assertEquals("demo.tool", callChain.getToolSteps().get(0).getToolName());
		assertEquals(callChain.getAnswerExplain().getToolSteps(), callChain.getToolSteps());
		assertEquals(List.of(step), callChain.getOrchestration().getSteps());
	}

	@Test
	void getSessionCallChainReturnsTraceWhenAnswerExplainIsMissing() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		when(sessionService.requireSessionForAgent(100L, 1L))
			.thenReturn(DataChatSession.builder().agentId(1L).build());
		SessionTraceStore sessionTraceStore = mock(SessionTraceStore.class);
		SessionTraceStore.TraceView trace = new SessionTraceStore.TraceView("100", "trace-1", "runtime-1", "1",
				1000L, 1100L, 100L, 1, null, List.of());
		when(sessionTraceStore.getTrace("100", "runtime-1")).thenReturn(Optional.of(trace));
		DataChatServiceImpl service = service(sessionService, mock(ChatMessageService.class), mock(LocalFileService.class),
				sessionTraceStore, mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class));

		SessionCallChainResp callChain = service.getSessionCallChain(100L, 1L, "runtime-1");

		assertEquals(trace, callChain.getTrace());
		assertTrue(callChain.getToolSteps().isEmpty());
	}

	@Test
	void getSessionMessages_rejectsEmployeeChannelSession() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		when(sessionService.requireSessionForAgent(100L, 1L))
			.thenReturn(DataChatSession.builder().agentId(1L).channelType("EMPLOYEE").build());
		DataChatServiceImpl service = service(sessionService, chatMessageService, mock(LocalFileService.class),
				sessionTraceStore(), mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class));

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> service.getSessionMessages(100L, 1L));

		assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
		verify(chatMessageService, never()).findVisibleBySessionId(100L, 1L);
	}

	@Test
	void queryAgentSessions_rejectsEmployeeChannel() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		DataChatServiceImpl service = service(sessionService, mock(ChatMessageService.class), mock(LocalFileService.class),
				sessionTraceStore(), mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class));
		ChatSessionPageQueryReq request = new ChatSessionPageQueryReq();
		request.setChannelType("EMPLOYEE");

		ResponseStatusException ex = assertThrows(ResponseStatusException.class,
				() -> service.queryAgentSessions(1L, request));

		assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
		verify(sessionService, never()).queryByAgentId(any(), any());
		verify(sessionService, never()).queryByAgentIdAndUserId(any(), any(), any());
	}

	@Test
	void getAgentSessions_returnsOnlyCurrentUserSessions() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		DataChatServiceImpl service = service(sessionService, mock(ChatMessageService.class), mock(LocalFileService.class),
				sessionTraceStore(), mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class), mockAuth("3"));

		service.getAgentSessions(1L);

		verify(sessionService).findByAgentIdAndUserId(1L, 3L);
		verify(sessionService, never()).findByAgentId(any());
	}

	@Test
	void getAgentSessions_forbidsWhenCurrentUserMissing() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.userId()).thenReturn("");
		DataChatServiceImpl service = service(sessionService, mock(ChatMessageService.class), mock(LocalFileService.class),
				sessionTraceStore(), mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class), mock(AgentModelConfigService.class), authenticationContext);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.getAgentSessions(1L));

		assertTrue(ex.getMessage().contains("禁止查看会话"), ex.getMessage());
		verify(sessionService, never()).findByAgentIdAndUserId(any(), any());
		verify(sessionService, never()).findByAgentId(any());
	}

	@Test
	void queryAgentSessions_passesCurrentUserToSessionQuery() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		DataChatServiceImpl service = service(sessionService, mock(ChatMessageService.class), mock(LocalFileService.class),
				sessionTraceStore(), mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class), mockAuth("3"));
		ChatSessionPageQueryReq request = new ChatSessionPageQueryReq();
		request.setAgentId(1L);

		service.queryAgentSessions(1L, request);

		verify(sessionService).queryByAgentIdAndUserId(1L, 3L, request);
		verify(sessionService, never()).queryByAgentId(any(), any());
	}

	@Test
	void queryAgentSessions_forbidsWhenCurrentUserUnresolvable() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.userId()).thenThrow(new IllegalStateException("no login"));
		DataChatServiceImpl service = service(sessionService, mock(ChatMessageService.class), mock(LocalFileService.class),
				sessionTraceStore(), mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class), mock(AgentModelConfigService.class), authenticationContext);
		ChatSessionPageQueryReq request = new ChatSessionPageQueryReq();

		CheckedException ex = assertThrows(CheckedException.class, () -> service.queryAgentSessions(1L, request));

		assertTrue(ex.getMessage().contains("禁止查看会话"), ex.getMessage());
		verify(sessionService, never()).queryByAgentIdAndUserId(any(), any(), any());
	}

	@Test
	void createSession_stampsCurrentUserAndIgnoresRequestUserId() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		DataChatServiceImpl service = service(sessionService, mock(ChatMessageService.class), mock(LocalFileService.class),
				sessionTraceStore(), mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class), mockAuth("3"));
		CreateChatSessionReq request = new CreateChatSessionReq();
		request.setAgentId(1L);
		request.setTitle("新会话");
		request.setUserId(99L);
		DataChatSession created = new DataChatSession(1L, "新会话", "active", 3L);
		created.setId(10L);
		when(sessionService.createSession(eq(1L), eq("新会话"), eq(3L), eq(ChatSessionChannelDict.WEB.getValue()), isNull(),
				isNull(), isNull(), isNull())).thenReturn(created);

		DataChatSession result = service.createSession(1L, request);

		assertEquals(3L, result.getUserId());
		verify(sessionService).createSession(1L, "新会话", 3L, ChatSessionChannelDict.WEB.getValue(), null, null, null, null);
	}

	@Test
	void saveMessage_rejectsNonUserRole() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		DataChatServiceImpl service = saveMessageService(sessionService, chatMessageService);
		ChatMessageReq request = userMessageReq();
		request.setRole("assistant");

		CheckedException ex = assertThrows(CheckedException.class, () -> service.saveMessage(100L, request));

		assertEquals("客户端仅允许保存用户消息", ex.getMessage());
		verify(chatMessageService, never()).saveMessage(any(), eq(1L));
	}

	@Test
	void saveMessage_treatsBlankRoleAsUser() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		when(chatMessageService.saveMessage(any(DataChatMessage.class), eq(1L))).thenAnswer(invocation -> {
			DataChatMessage message = invocation.getArgument(0);
			message.setId(5L);
			return message;
		});
		when(chatMessageService.findBySessionId(100L)).thenReturn(List.of());
		DataChatServiceImpl service = saveMessageService(sessionService, chatMessageService);
		ChatMessageReq request = userMessageReq();
		request.setRole("  ");

		DataChatMessage saved = service.saveMessage(100L, request);

		assertEquals("user", saved.getRole());
		ArgumentCaptor<DataChatMessage> captor = ArgumentCaptor.forClass(DataChatMessage.class);
		verify(chatMessageService).saveMessage(captor.capture(), eq(1L));
		assertEquals("user", captor.getValue().getRole());
	}

	@Test
	void saveMessage_returnsExistingUserMessageForSameClientRequestId() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		DataChatMessage existing = DataChatMessage.builder()
			.id(8L)
			.sessionId(100L)
			.role("user")
			.content("hello")
			.metadata("{\"clientRequestId\":\"req-1\"}")
			.build();
		when(chatMessageService.findBySessionId(100L)).thenReturn(List.of(existing));
		DataChatServiceImpl service = saveMessageService(sessionService, chatMessageService);
		ChatMessageReq request = userMessageReq();
		request.setContent("hello again");
		request.setClientRequestId("req-1");

		DataChatMessage result = service.saveMessage(100L, request);

		assertSame(existing, result);
		verify(chatMessageService, never()).saveMessage(any(), eq(1L));
		verify(sessionService, never()).updateSessionTime(eq(100L), eq(1L));
	}

	@Test
	void saveMessage_putsClientRequestIdIntoMetadataObject() throws Exception {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		when(chatMessageService.saveMessage(any(DataChatMessage.class), eq(1L))).thenAnswer(invocation -> {
			DataChatMessage message = invocation.getArgument(0);
			message.setId(5L);
			return message;
		});
		when(chatMessageService.findBySessionId(100L)).thenReturn(List.of());
		DataChatServiceImpl service = saveMessageService(sessionService, chatMessageService);
		ChatMessageReq request = userMessageReq();
		request.setMetadata("{\"attachments\":[]}");
		request.setClientRequestId("req-2");

		service.saveMessage(100L, request);

		ArgumentCaptor<DataChatMessage> captor = ArgumentCaptor.forClass(DataChatMessage.class);
		verify(chatMessageService).saveMessage(captor.capture(), eq(1L));
		JsonNode metadata = objectMapper.readTree(captor.getValue().getMetadata());
		assertEquals("req-2", metadata.path("clientRequestId").asText());
		assertTrue(metadata.path("attachments").isArray());
	}

	@Test
	void saveMessage_putsClientRequestIdWhenMetadataBlank() throws Exception {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		when(chatMessageService.saveMessage(any(DataChatMessage.class), eq(1L))).thenAnswer(invocation -> {
			DataChatMessage message = invocation.getArgument(0);
			message.setId(5L);
			return message;
		});
		when(chatMessageService.findBySessionId(100L)).thenReturn(List.of());
		DataChatServiceImpl service = saveMessageService(sessionService, chatMessageService);
		ChatMessageReq request = userMessageReq();
		request.setClientRequestId("req-3");

		service.saveMessage(100L, request);

		ArgumentCaptor<DataChatMessage> captor = ArgumentCaptor.forClass(DataChatMessage.class);
		verify(chatMessageService).saveMessage(captor.capture(), eq(1L));
		assertEquals("req-3", objectMapper.readTree(captor.getValue().getMetadata()).path("clientRequestId").asText());
	}

	@Test
	void createSession_forbidsWhenCurrentUserMissing() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.userId()).thenReturn("");
		DataChatServiceImpl service = service(sessionService, mock(ChatMessageService.class), mock(LocalFileService.class),
				sessionTraceStore(), mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class), mock(AgentModelConfigService.class), authenticationContext);
		CreateChatSessionReq request = new CreateChatSessionReq();
		request.setAgentId(1L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.createSession(1L, request));

		assertTrue(ex.getMessage().contains("禁止查看会话"), ex.getMessage());
		verify(sessionService, never()).createSession(any(), any(), any(), any(), any(), any(), any(), any());
	}

	private DataChatServiceImpl saveMessageService(DataChatSessionService sessionService,
			ChatMessageService chatMessageService) {
		when(sessionService.requireSessionForAgent(100L, 1L))
			.thenReturn(DataChatSession.builder().agentId(1L).build());
		return service(sessionService, chatMessageService, mock(LocalFileService.class), sessionTraceStore(),
				mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class));
	}

	private static ChatMessageReq userMessageReq() {
		ChatMessageReq request = new ChatMessageReq();
		request.setSessionId(100L);
		request.setAgentId(1L);
		request.setRole("user");
		request.setContent("hello");
		return request;
	}

	private DataChatServiceImpl service(ChatMessageService chatMessageService, LocalFileService localFileService) {
		return service(mock(DataChatSessionService.class), chatMessageService, localFileService,
				mock(SessionTraceStore.class), mock(AnswerTraceExplainStore.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class));
	}

	private DataChatServiceImpl service(DataChatSessionService sessionService, ChatMessageService chatMessageService,
			LocalFileService localFileService, SessionTraceStore sessionTraceStore,
			AnswerTraceExplainStore answerTraceExplainStore, AgentOrchestrationRunMapper runMapper,
			AgentOrchestrationStepMapper stepMapper) {
		return service(sessionService, chatMessageService, localFileService, sessionTraceStore, answerTraceExplainStore,
				runMapper, stepMapper, mock(AgentModelConfigService.class));
	}

	private DataChatServiceImpl service(DataChatSessionService sessionService, ChatMessageService chatMessageService,
			LocalFileService localFileService, SessionTraceStore sessionTraceStore,
			AnswerTraceExplainStore answerTraceExplainStore, AgentOrchestrationRunMapper runMapper,
			AgentOrchestrationStepMapper stepMapper, AgentModelConfigService agentModelConfigService) {
		return service(sessionService, chatMessageService, localFileService, sessionTraceStore, answerTraceExplainStore,
				runMapper, stepMapper, agentModelConfigService, mockAuth("3"));
	}

	private DataChatServiceImpl service(DataChatSessionService sessionService, ChatMessageService chatMessageService,
			LocalFileService localFileService, SessionTraceStore sessionTraceStore,
			AnswerTraceExplainStore answerTraceExplainStore, AgentOrchestrationRunMapper runMapper,
			AgentOrchestrationStepMapper stepMapper, AuthenticationContext authenticationContext) {
		return service(sessionService, chatMessageService, localFileService, sessionTraceStore, answerTraceExplainStore,
				runMapper, stepMapper, mock(AgentModelConfigService.class), authenticationContext);
	}

	@SuppressWarnings("unchecked")
	private DataChatServiceImpl service(DataChatSessionService sessionService, ChatMessageService chatMessageService,
			LocalFileService localFileService, SessionTraceStore sessionTraceStore,
			AnswerTraceExplainStore answerTraceExplainStore, AgentOrchestrationRunMapper runMapper,
			AgentOrchestrationStepMapper stepMapper, AgentModelConfigService agentModelConfigService,
			AuthenticationContext authenticationContext) {
		DataAgentThinkingPermissionService permissionService = mock(DataAgentThinkingPermissionService.class);
		when(permissionService.canViewAnswerExplain()).thenReturn(true);
		when(permissionService.canViewCallChain()).thenReturn(true);
		return new DataChatServiceImpl(sessionService, chatMessageService,
				agentModelConfigService, mock(DataAgentService.class), runMapper, stepMapper,
				mock(DataChatTurnMapper.class), mock(SessionTitleService.class), mock(ReportTemplateUtil.class), sessionTraceStore,
				answerTraceExplainStore, mock(AnalysisReportService.class), localFileService, objectMapper,
				permissionService, new AgentRuntimeRegistry(),
				mock(AgentScopeNativeSessionService.class), mock(ContextCompressionService.class),
				mock(DynamicModelFactory.class), mock(AgentScopeModelFactory.class), authenticationContext,
				mock(ObjectProvider.class));
	}

	@SuppressWarnings("unchecked")
	private DataChatServiceImpl serviceWithAs2(DataChatSessionService sessionService,
			ChatMessageService chatMessageService, AgentModelConfigService agentModelConfigService,
			ContextCompressionService compressionService, ObjectProvider<V2AgentStateStore> v2StoreProvider,
			AgentScopeNativeSessionService nativeSessionService) {
		DataAgentThinkingPermissionService permissionService = mock(DataAgentThinkingPermissionService.class);
		when(permissionService.canViewAnswerExplain()).thenReturn(true);
		when(permissionService.canViewCallChain()).thenReturn(true);
		return new DataChatServiceImpl(sessionService, chatMessageService, agentModelConfigService,
				mock(DataAgentService.class), mock(AgentOrchestrationRunMapper.class),
				mock(AgentOrchestrationStepMapper.class), mock(DataChatTurnMapper.class),
				mock(SessionTitleService.class), mock(ReportTemplateUtil.class), mock(SessionTraceStore.class),
				mock(AnswerTraceExplainStore.class), mock(AnalysisReportService.class), mock(LocalFileService.class),
				objectMapper, permissionService, new AgentRuntimeRegistry(), nativeSessionService, compressionService,
				mock(DynamicModelFactory.class), mock(AgentScopeModelFactory.class), mockAuth("3"), v2StoreProvider);
	}

	private static DataChatSession as2Session() {
		DataChatSession session = DataChatSession.builder().agentId(1L).tenantId("tenant-1").userId(7L).build();
		session.setId(100L);
		return session;
	}

	private static Msg msg(MsgRole role, String text) {
		return Msg.builder().name(role.name().toLowerCase()).role(role).textContent(text).build();
	}

	private AuthenticationContext mockAuth(String userId) {
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		when(authenticationContext.userId()).thenReturn(userId);
		return authenticationContext;
	}

	private SessionTraceStore sessionTraceStore() {
		return mock(SessionTraceStore.class);
	}

}
