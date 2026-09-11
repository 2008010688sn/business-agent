/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tokenusage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.AgentTokenUsageDay;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageOverviewResp;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageQueryReq;
import com.sn68.agent.dataagent.entity.AgentTokenUsage;
import com.sn68.agent.dataagent.repository.AgentTokenUsageDayMapper;
import com.sn68.agent.dataagent.repository.AgentTokenUsageMapper;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class AgentTokenUsageServiceTest {

	private final AgentTokenUsageMapper usageMapper = mock(AgentTokenUsageMapper.class);

	private final AgentTokenUsageDayMapper usageDayMapper = mock(AgentTokenUsageDayMapper.class);

	private final AgentUsageLimitService limitService = mock(AgentUsageLimitService.class);

	private final PlatformScopePermissionService platformScopePermissionService = mock(
			PlatformScopePermissionService.class);

	private final AgentTokenUsageService service = new AgentTokenUsageService(usageMapper,
			usageDayMapper, mock(DataChatTurnMapper.class), limitService, new ObjectMapper(),
			platformScopePermissionService);

	@BeforeEach
	void bindTenantUser() {
		when(platformScopePermissionService.isPlatformAdmin()).thenReturn(false);
		when(platformScopePermissionService.requireCurrentTenantId(anyString())).thenReturn("tenant-1");
	}

	@Test
	void queryOverviewAggregatesRowsWithSingleUsageListQuery() {
		AgentTokenUsageQueryReq request = new AgentTokenUsageQueryReq();
		request.setCurrent(9);
		request.setSize(5);
		request.setGroupBy("USER");
		request.setAgentId(100L);
		request.setStartTime(Instant.parse("2026-07-01T00:00:00Z"));
		request.setEndTime(Instant.parse("2026-07-02T23:59:59Z"));
		List<AgentTokenUsage> rows = List.of(
				usage("u1", "Alice", 100L, "Support Agent", 10L, "qwen-max", "2026-07-01T02:00:00Z", 4L, 6L, 10L,
						AgentTokenUsageService.METERING_ACTUAL, AgentTokenUsageService.STATUS_SUCCESS, null),
				usage("u2", "Bob", 101L, "Analysis Agent", 11L, "deepseek-chat", "2026-07-01T03:00:00Z", 8L, 12L, 20L,
						AgentTokenUsageService.METERING_ESTIMATED, AgentTokenUsageService.STATUS_FAILED, "quota.limit"),
				usage("u1", "Alice", 100L, "Support Agent", 10L, "qwen-max", "2026-07-02T04:00:00Z", 2L, 3L, 5L,
						AgentTokenUsageService.METERING_UNKNOWN, AgentTokenUsageService.STATUS_CANCELLED, null));
		when(usageMapper.selectUsageList(argThat(query -> query != null && query != request && query.getAgentId().equals(100L)
				&& query.getCurrent() == 1 && query.getSize() == 20 && query.getGroupBy() == null), eq("tenant-1")))
			.thenReturn(rows);

		AgentTokenUsageOverviewResp overview = service.queryOverview(request);

		verify(usageMapper, times(1))
			.selectUsageList(argThat(query -> query != null && query != request && query.getGroupBy() == null),
					eq("tenant-1"));
		assertEquals(3L, overview.summary().requestCount());
		assertEquals(1L, overview.summary().successCount());
		assertEquals(1L, overview.summary().failedCount());
		assertEquals(35L, overview.summary().totalTokens());
		assertEquals(14L, overview.summary().promptTokens());
		assertEquals(21L, overview.summary().completionTokens());
		assertEquals(10L, overview.summary().actualTokens());
		assertEquals(20L, overview.summary().estimatedTokens());
		assertEquals(1L, overview.summary().unknownCount());
		assertEquals(1L, overview.summary().blockedCount());
		assertEquals(2, overview.userBreakdown().size());
		assertEquals("u2", overview.userBreakdown().get(0).groupKey());
		assertEquals("Bob", overview.userBreakdown().get(0).groupName());
		assertEquals(20L, overview.userBreakdown().get(0).totalTokens());
		assertEquals(2, overview.agentBreakdown().size());
		assertEquals("100", overview.agentBreakdown().get(1).groupKey());
		assertEquals("Support Agent", overview.agentBreakdown().get(1).groupName());
		assertEquals(15L, overview.agentBreakdown().get(1).totalTokens());
		assertEquals(2, overview.modelBreakdown().size());
		assertEquals("deepseek-chat", overview.modelBreakdown().get(0).groupKey());
		assertEquals(List.of("2026-07-01", "2026-07-02"),
				overview.dayBreakdown().stream().map(item -> item.groupKey()).toList());
		assertEquals("USER", request.getGroupBy());
	}

	@Test
	void queryOverviewFallsBackToIdsWhenSnapshotNamesAreMissing() {
		AgentTokenUsageQueryReq request = new AgentTokenUsageQueryReq();
		List<AgentTokenUsage> rows = List.of(
				usage("u1", null, 100L, null, 10L, "qwen-max", "2026-07-01T02:00:00Z", 4L, 6L, 10L,
						AgentTokenUsageService.METERING_ACTUAL, AgentTokenUsageService.STATUS_SUCCESS, null));
		when(usageMapper.selectUsageList(argThat(query -> true), eq("tenant-1"))).thenReturn(rows);

		AgentTokenUsageOverviewResp overview = service.queryOverview(request);

		assertEquals("u1", overview.userBreakdown().get(0).groupName());
		assertEquals("100", overview.agentBreakdown().get(0).groupName());
	}

	@Test
	void queryOverviewIgnoresTheTenantIdSuppliedByANonPlatformAdminCaller() {
		AgentTokenUsageQueryReq request = new AgentTokenUsageQueryReq();
		request.setTenantId("tenant-2");

		service.queryOverview(request);

		verify(usageMapper).selectUsageList(argThat(query -> query != null && query.getTenantId() == null),
				eq("tenant-1"));
		verify(usageMapper, never()).selectUsageListAcrossTenants(any());
	}

	@Test
	void queryDetailsPageForcesTheAuthenticatedTenantAndStripsTheRequestBodyTenantId() {
		AgentTokenUsageQueryReq request = new AgentTokenUsageQueryReq();
		request.setTenantId("tenant-2");

		service.queryDetailsPage(request);

		assertNull(request.getTenantId());
		verify(usageMapper).selectUsagePage(any(), same(request), eq("tenant-1"));
		verify(usageMapper, never()).selectUsagePageAcrossTenants(any(), any());
	}

	@Test
	void queryDetailsPageIsRejectedWhenTheTenantContextIsUnavailable() {
		when(platformScopePermissionService.requireCurrentTenantId(anyString()))
			.thenThrow(CheckedException.forbidden("当前登录信息缺少租户上下文，无法查询Token 用量"));

		assertThrows(CheckedException.class, () -> service.queryDetailsPage(new AgentTokenUsageQueryReq()));

		verify(usageMapper, never()).selectUsagePage(any(), any(), anyString());
		verify(usageMapper, never()).selectUsagePageAcrossTenants(any(), any());
	}

	@Test
	void platformAdminAlsoQueriesTheCurrentLoginTenant() {
		when(platformScopePermissionService.isPlatformAdmin()).thenReturn(true);
		AgentTokenUsageQueryReq targeted = new AgentTokenUsageQueryReq();
		targeted.setTenantId("tenant-2");

		service.queryDetailsPage(targeted);
		service.queryDetailsPage(new AgentTokenUsageQueryReq());

		verify(usageMapper, org.mockito.Mockito.times(2)).selectUsagePage(any(), any(), eq("tenant-1"));
		verify(usageMapper, never()).selectUsagePageAcrossTenants(any(), any());
	}

	@Test
	void recordEstimatedUsagePersistsSnapshotNames() {
		AgentTokenUsageContext context = AgentTokenUsageContext.builder()
			.tenantId("tenant-1")
			.userId("u1")
			.userNickName("Alice")
			.agentId(100L)
			.agentName("Support Agent")
			.modelConfigId(10L)
			.provider("dashscope")
			.modelName("qwen-max")
			.usageSource(AgentTokenUsageService.SOURCE_AGENT_REACT)
			.maxTokens(5L)
			.build();
		ArgumentCaptor<AgentTokenUsage> usageCaptor = ArgumentCaptor.forClass(AgentTokenUsage.class);
		ArgumentCaptor<AgentTokenUsageDay> dayCaptor = ArgumentCaptor.forClass(AgentTokenUsageDay.class);

		service.recordEstimatedUsage(context, 7L, AgentTokenUsageService.STATUS_SUCCESS);

		verify(usageMapper).insert(usageCaptor.capture());
		verify(usageDayMapper).upsertDelta(dayCaptor.capture());
		assertEquals("Alice", usageCaptor.getValue().getUserNickName());
		assertEquals("Support Agent", usageCaptor.getValue().getAgentName());
		assertEquals("Alice", dayCaptor.getValue().getUserNickName());
		assertEquals("Support Agent", dayCaptor.getValue().getAgentName());
	}

	@Test
	void recordEstimatedUsageAllowsMissingAgentNameSnapshot() {
		AgentTokenUsageContext context = AgentTokenUsageContext.builder()
			.userId("u1")
			.userNickName("Alice")
			.agentId(100L)
			.maxTokens(5L)
			.usageSource(AgentTokenUsageService.SOURCE_AGENT_REACT)
			.build();
		ArgumentCaptor<AgentTokenUsage> usageCaptor = ArgumentCaptor.forClass(AgentTokenUsage.class);

		service.recordEstimatedUsage(context, 7L, AgentTokenUsageService.STATUS_SUCCESS);

		verify(usageMapper).insert(usageCaptor.capture());
		assertNull(usageCaptor.getValue().getAgentName());
	}

	@Test
	void callAndRecordForwardsRoleSeparatedPrompt() {
		ChatModel chatModel = mock(ChatModel.class);
		Prompt prompt = new Prompt(List.of(new SystemMessage("system rules"), new UserMessage("user evidence")));
		AgentTokenUsageContext context = AgentTokenUsageContext.builder()
			.agentId(100L)
			.maxTokens(0L)
			.usageSource("SKILL_KNOWLEDGE")
			.build();
		when(limitService.preCheckAndReserve(any(), anyLong())).thenReturn(AgentUsageReservation.empty());
		when(chatModel.call(same(prompt)))
			.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("grounded answer")))));

		String answer = service.callAndRecord(chatModel, prompt, context);

		assertEquals("grounded answer", answer);
		verify(chatModel).call(same(prompt));
	}

	@Test
	void callAndRecordPersistsFinishReasonWithTokenSnapshot() throws Exception {
		ChatModel chatModel = mock(ChatModel.class);
		AgentTokenUsageContext context = AgentTokenUsageContext.builder()
			.agentId(100L)
			.maxTokens(1024L)
			.usageSource("FLOW_EXTRACT")
			.build();
		when(limitService.preCheckAndReserve(any(), anyLong())).thenReturn(AgentUsageReservation.empty());
		when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
				new AssistantMessage("{}"), ChatGenerationMetadata.builder().finishReason("LENGTH").build()))));
		ArgumentCaptor<AgentTokenUsage> usageCaptor = ArgumentCaptor.forClass(AgentTokenUsage.class);

		service.callAndRecord(chatModel, "extract", context);

		verify(usageMapper).insert(usageCaptor.capture());
		Map<?, ?> rawUsage = new ObjectMapper().readValue(usageCaptor.getValue().getRawUsageJson(), Map.class);
		assertEquals("LENGTH", rawUsage.get("finishReason"));
		assertEquals(usageCaptor.getValue().getPromptTokens().intValue(), rawUsage.get("promptTokens"));
		assertEquals(usageCaptor.getValue().getCompletionTokens().intValue(), rawUsage.get("completionTokens"));
		assertEquals(usageCaptor.getValue().getTotalTokens().intValue(), rawUsage.get("totalTokens"));
	}

	@Test
	void callAndRecordDetailedReturnsFinishReasonAndActualUsage() {
		ChatModel chatModel = mock(ChatModel.class);
		AgentTokenUsageContext context = AgentTokenUsageContext.builder()
			.agentId(100L)
			.maxTokens(2048L)
			.usageSource(AgentTokenUsageService.SOURCE_SKILL_DETERMINISTIC)
			.build();
		when(limitService.preCheckAndReserve(any(), anyLong())).thenReturn(AgentUsageReservation.empty());
		when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
				new AssistantMessage("{\"sql\":\"select 1\"}"),
				ChatGenerationMetadata.builder().finishReason("LENGTH").build())),
				ChatResponseMetadata.builder().usage(new DefaultUsage(11, 7, 18)).build()));

		AgentModelCallResult result = service.callAndRecordDetailed(chatModel, "plan", context);

		assertEquals("{\"sql\":\"select 1\"}", result.text());
		assertEquals("LENGTH", result.finishReason());
		assertEquals(11L, result.promptTokens());
		assertEquals(7L, result.completionTokens());
		assertEquals(18L, result.totalTokens());
		assertEquals(AgentTokenUsageService.METERING_ACTUAL, result.meteringMode());
	}

	@Test
	void callAndRecordStructuredRetainsToolArgumentsOnlyInMemory() {
		ChatModel chatModel = mock(ChatModel.class);
		AgentTokenUsageContext context = AgentTokenUsageContext.builder()
			.agentId(100L)
			.maxTokens(256L)
			.usageSource("FLOW_EXTRACT")
			.build();
		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function",
				"submit_flow_patch", "{\"set\":{\"companyName\":\"Acme\"}}");
		ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("ignored")
			.toolCalls(List.of(toolCall)).build(), ChatGenerationMetadata.builder().finishReason("STOP").build())),
				ChatResponseMetadata.builder().usage(new DefaultUsage(11, 7, 18)).build());
		when(limitService.preCheckAndReserve(any(), anyLong())).thenReturn(AgentUsageReservation.empty());
		when(chatModel.call(any(Prompt.class))).thenReturn(response);

		AgentStructuredModelCallResult result = service.callAndRecordStructured(chatModel, "extract", context);

		assertEquals(response, result.chatResponse());
		assertEquals(List.of(toolCall), result.toolCalls());
		assertEquals("ignored", result.text());
		assertEquals("STOP", result.finishReason());
	}

	@Test
	void callAndRecordDetailedUsesEffectiveContextMaxTokensForReservationAndEstimatedUsage() {
		ChatModel chatModel = mock(ChatModel.class);
		AgentTokenUsageContext context = AgentTokenUsageContext.builder()
			.agentId(100L)
			.maxTokens(20_000L)
			.usageSource(AgentTokenUsageService.SOURCE_SKILL_DETERMINISTIC)
			.build()
			.toBuilder()
			.maxTokens(2048L)
			.build();
		when(limitService.preCheckAndReserve(any(), anyLong())).thenReturn(AgentUsageReservation.empty());
		when(chatModel.call(any(Prompt.class)))
			.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"sql\":\"select 1\"}")))));

		AgentModelCallResult result = service.callAndRecordDetailed(chatModel, "x", context);

		verify(limitService).preCheckAndReserve(argThat(item -> item != null && item.maxTokens() == 2048L), eq(2049L));
		assertEquals(1L, result.promptTokens());
		assertEquals(2048L, result.completionTokens());
		assertEquals(2049L, result.totalTokens());
		assertEquals(AgentTokenUsageService.METERING_ESTIMATED, result.meteringMode());
	}

	@Test
	void callAndRecordKeepsZeroUsageAsActualForCompatibility() {
		ChatModel chatModel = mock(ChatModel.class);
		AgentTokenUsageContext context = AgentTokenUsageContext.builder()
			.agentId(100L)
			.maxTokens(20_000L)
			.usageSource(AgentTokenUsageService.SOURCE_AGENT_REACT)
			.build();
		when(limitService.preCheckAndReserve(any(), anyLong())).thenReturn(AgentUsageReservation.empty());
		when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
				new AssistantMessage("answer"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(0, 0, 0)).build()));
		ArgumentCaptor<AgentTokenUsage> usageCaptor = ArgumentCaptor.forClass(AgentTokenUsage.class);

		assertEquals("answer", service.callAndRecord(chatModel, "prompt", context));

		verify(usageMapper).insert(usageCaptor.capture());
		assertEquals(AgentTokenUsageService.METERING_ACTUAL, usageCaptor.getValue().getMeteringMode());
		assertEquals(0L, usageCaptor.getValue().getTotalTokens());
	}

	private AgentTokenUsage usage(String userId, String userNickName, Long agentId, String agentName, Long modelConfigId,
			String modelName, String createTime, Long promptTokens, Long completionTokens, Long totalTokens,
			String meteringMode, String status, String errorCode) {
		return AgentTokenUsage.builder()
			.userId(userId)
			.userNickName(userNickName)
			.agentId(agentId)
			.agentName(agentName)
			.modelConfigId(modelConfigId)
			.modelName(modelName)
			.promptTokens(promptTokens)
			.completionTokens(completionTokens)
			.totalTokens(totalTokens)
			.meteringMode(meteringMode)
			.status(status)
			.errorCode(errorCode)
			.createTime(Instant.parse(createTime))
			.build();
	}

}
