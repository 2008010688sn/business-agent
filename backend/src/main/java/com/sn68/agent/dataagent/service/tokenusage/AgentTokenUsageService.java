/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tokenusage;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeErrorClassifier;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageBreakdownResp;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageOverviewResp;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageQueryReq;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageSummaryResp;
import com.sn68.agent.dataagent.entity.AgentTokenUsage;
import com.sn68.agent.dataagent.entity.AgentTokenUsageDay;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.repository.AgentTokenUsageDayMapper;
import com.sn68.agent.dataagent.repository.AgentTokenUsageMapper;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AgentToken用量组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTokenUsageService {

	public static final String METERING_ACTUAL = "ACTUAL";

	public static final String METERING_ESTIMATED = "ESTIMATED";

	public static final String METERING_UNKNOWN = "UNKNOWN";

	public static final String STATUS_SUCCESS = "SUCCESS";

	public static final String STATUS_FAILED = "FAILED";

	public static final String STATUS_CANCELLED = "CANCELLED";

	public static final String SOURCE_AGENT_REACT = "AGENT_REACT";

	public static final String SOURCE_KNOWLEDGE_FAST_PATH = "KNOWLEDGE_FAST_PATH";

	public static final String SOURCE_SKILL_DETERMINISTIC = "SKILL_DETERMINISTIC";

	public static final String SOURCE_ORCHESTRATION_ROUTE = "ORCHESTRATION_ROUTE";

	public static final String SOURCE_ORCHESTRATION_SUMMARY = "ORCHESTRATION_SUMMARY";

	public static final String SOURCE_ANALYSIS_REPORT = "ANALYSIS_REPORT";

	private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Shanghai");

	private final AgentTokenUsageMapper usageMapper;

	private final AgentTokenUsageDayMapper usageDayMapper;

	private final DataChatTurnMapper turnMapper;

	private final AgentUsageLimitService limitService;

	private final ObjectMapper objectMapper;

	private final PlatformScopePermissionService platformScopePermissionService;

	/**
	 * 创建AgentToken用量。
	 */
	public AgentTokenUsageContext buildContext(AgentRequest request, ModelConfigDTO modelConfig, String usageSource) {
		AgentTokenUsageContext context = AgentTokenUsageContext.from(request, modelConfig, usageSource);
		return context.toBuilder().teamIdsJson(writeTeamIds(request == null ? null : request.getTeamIdsSnapshot())).build();
	}

	/**
	 * 处理AgentToken用量。
	 */
	public AgentUsageReservation preCheckAndReserve(AgentTokenUsageContext context, long estimatedPromptTokens) {
		long reservedTokens = Math.max(1L, estimatedPromptTokens) + Math.max(0L, value(context == null ? null : context.maxTokens()));
		return limitService.preCheckAndReserve(context == null ? null
				: context.toBuilder().estimatedPromptTokens(estimatedPromptTokens).build(), reservedTokens);
	}

	/**
	 * 处理AgentToken用量。
	 */
	public String callAndRecord(ChatModel chatModel, String prompt, AgentTokenUsageContext context) {
		return callAndRecord(chatModel, new Prompt(List.of(new UserMessage(prompt))), context);
	}

	/**
	 * 处理包含不同消息角色的模型调用并记录 Token 用量。
	 */
	public String callAndRecord(ChatModel chatModel, Prompt prompt, AgentTokenUsageContext context) {
		return callAndRecordInternal(chatModel, prompt, context, true).text();
	}

	/**
	 * Calls the model and returns the response metadata recorded for this invocation.
	 */
	public AgentModelCallResult callAndRecordDetailed(ChatModel chatModel, String prompt,
			AgentTokenUsageContext context) {
		return callAndRecordDetailed(chatModel, new Prompt(List.of(new UserMessage(prompt))), context);
	}

	/**
	 * Calls the model with role-separated messages and returns its recorded response metadata.
	 */
	public AgentModelCallResult callAndRecordDetailed(ChatModel chatModel, Prompt prompt,
			AgentTokenUsageContext context) {
		return callAndRecordInternal(chatModel, prompt, context, false).asTextResult();
	}

	/**
	 * Calls a structured model and retains its tool-call arguments only in memory
	 * for the immediate protocol decoder.
	 */
	public AgentStructuredModelCallResult callAndRecordStructured(ChatModel chatModel, String prompt,
			AgentTokenUsageContext context) {
		return callAndRecordStructured(chatModel, new Prompt(List.of(new UserMessage(prompt))), context);
	}

	/**
	 * Calls a structured model with role-separated messages and retains the raw
	 * {@link ChatResponse} only in the returned in-memory value.
	 */
	public AgentStructuredModelCallResult callAndRecordStructured(ChatModel chatModel, Prompt prompt,
			AgentTokenUsageContext context) {
		return callAndRecordInternal(chatModel, prompt, context, false);
	}

	private AgentStructuredModelCallResult callAndRecordInternal(ChatModel chatModel, Prompt prompt,
			AgentTokenUsageContext context, boolean zeroUsageIsActual) {
		long start = System.nanoTime();
		long estimatedPromptTokens = estimateTokens(prompt.getContents());
		AgentUsageReservation reservation;
		try {
			reservation = preCheckAndReserve(context, estimatedPromptTokens);
		}
		catch (RuntimeException ex) {
			recordUnknownUsage(context == null ? null : context.toBuilder().estimatedPromptTokens(estimatedPromptTokens).build(),
					STATUS_FAILED, ex, AgentUsageReservation.empty(), elapsedMs(start));
			throw ex;
		}
		try {
			ChatResponse response = chatModel.call(prompt);
			Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
			String finishReason = finishReason(response);
			long durationMs = elapsedMs(start);
			if (usage != null && (zeroUsageIsActual || hasActualUsage(usage))) {
				recordActualUsage(context.toBuilder().estimatedPromptTokens(estimatedPromptTokens).build(), usage,
						STATUS_SUCCESS, reservation, durationMs, finishReason);
				long promptTokens = token(usage.getPromptTokens());
				long completionTokens = token(usage.getCompletionTokens());
				long totalTokens = token(usage.getTotalTokens());
				if (totalTokens <= 0L) {
					totalTokens = promptTokens + completionTokens;
				}
				return structuredResult(response, finishReason, promptTokens, completionTokens, totalTokens,
						METERING_ACTUAL, durationMs);
			}
			recordEstimatedUsage(context.toBuilder().estimatedPromptTokens(estimatedPromptTokens).build(),
					estimatedPromptTokens, STATUS_SUCCESS, reservation, durationMs, finishReason);
			long promptTokens = Math.max(1L, estimatedPromptTokens);
			long completionTokens = Math.max(0L, value(context.maxTokens()));
			return structuredResult(response, finishReason, promptTokens, completionTokens, promptTokens + completionTokens,
					METERING_ESTIMATED, durationMs);
		}
		catch (RuntimeException ex) {
			recordUnknownUsage(context.toBuilder().estimatedPromptTokens(estimatedPromptTokens).build(), STATUS_FAILED, ex,
					reservation, elapsedMs(start));
			throw ex;
		}
	}

	private boolean hasActualUsage(Usage usage) {
		return usage != null && (token(usage.getPromptTokens()) > 0L || token(usage.getCompletionTokens()) > 0L
				|| token(usage.getTotalTokens()) > 0L);
	}

	/**
	 * 保存AgentToken用量。
	 */
	public void recordActualUsage(AgentTokenUsageContext context, Usage usage, String status) {
		recordActualUsage(context, usage, status, AgentUsageReservation.empty(), null);
	}

	/**
	 * 保存AgentToken用量。
	 */
	public void recordActualUsage(AgentTokenUsageContext context, Usage usage, String status,
			AgentUsageReservation reservation, Long durationMs) {
		recordActualUsage(context, usage, status, reservation, durationMs, null);
	}

	private void recordActualUsage(AgentTokenUsageContext context, Usage usage, String status,
			AgentUsageReservation reservation, Long durationMs, String finishReason) {
		if (usage == null) {
			recordUnknownUsage(context, status, null, reservation, durationMs);
			return;
		}
		long promptTokens = token(usage.getPromptTokens());
		long completionTokens = token(usage.getCompletionTokens());
		long totalTokens = token(usage.getTotalTokens());
		if (totalTokens <= 0L) {
			totalTokens = promptTokens + completionTokens;
		}
		limitService.settleActualUsage(reservation, totalTokens);
		insertUsage(context, promptTokens, completionTokens, totalTokens, METERING_ACTUAL, status, durationMs, null, null,
				rawUsage(promptTokens, completionTokens, totalTokens, finishReason));
	}

	/**
	 * 保存AgentToken用量。
	 */
	public void recordEstimatedUsage(AgentTokenUsageContext context, long estimatedPromptTokens, String status) {
		recordEstimatedUsage(context, estimatedPromptTokens, status, AgentUsageReservation.empty(), null);
	}

	/**
	 * 保存AgentToken用量。
	 */
	public void recordEstimatedUsage(AgentTokenUsageContext context, long estimatedPromptTokens, String status,
			AgentUsageReservation reservation, Long durationMs) {
		recordEstimatedUsage(context, estimatedPromptTokens, status, reservation, durationMs, null);
	}

	private void recordEstimatedUsage(AgentTokenUsageContext context, long estimatedPromptTokens, String status,
			AgentUsageReservation reservation, Long durationMs, String finishReason) {
		long outputTokens = Math.max(0L, value(context == null ? null : context.maxTokens()));
		long totalTokens = Math.max(1L, estimatedPromptTokens) + outputTokens;
		limitService.settleActualUsage(reservation, totalTokens);
		insertUsage(context, Math.max(1L, estimatedPromptTokens), outputTokens, totalTokens, METERING_ESTIMATED, status,
				durationMs, null, null,
				rawUsage(Math.max(1L, estimatedPromptTokens), outputTokens, totalTokens, finishReason));
	}

	/**
	 * 保存AgentToken用量。
	 */
	public void recordUnknownUsage(AgentTokenUsageContext context, String status, Throwable error) {
		recordUnknownUsage(context, status, error, AgentUsageReservation.empty(), null);
	}

	/**
	 * 保存AgentToken用量。
	 */
	public void recordUnknownUsage(AgentTokenUsageContext context, String status, Throwable error,
			AgentUsageReservation reservation, Long durationMs) {
		limitService.releaseReservation(reservation);
		insertUsage(context, 0L, 0L, 0L, METERING_UNKNOWN, status, durationMs, errorCode(error),
				error == null ? null : error.getMessage(), rawUsage(0L, 0L, 0L, null));
	}

	/**
	 * 查询AgentToken用量。
	 */
	public AgentTokenUsageSummaryResp querySummary(AgentTokenUsageQueryReq request) {
		return toSummary(selectAuthorizedUsageList(request));
	}

	/**
	 * 查询AgentToken用量。
	 */
	public AgentTokenUsageOverviewResp queryOverview(AgentTokenUsageQueryReq request) {
		List<AgentTokenUsage> rows = selectAuthorizedUsageList(copyOverviewQuery(request));
		return AgentTokenUsageOverviewResp.builder()
			.summary(toSummary(rows))
			.userBreakdown(toBreakdowns(rows, "USER"))
			.agentBreakdown(toBreakdowns(rows, "AGENT"))
			.modelBreakdown(toBreakdowns(rows, "MODEL"))
			.dayBreakdown(toBreakdowns(rows, "DAY"))
			.build();
	}

	private AgentTokenUsageSummaryResp toSummary(List<AgentTokenUsage> sourceRows) {
		List<AgentTokenUsage> rows = sourceRows == null ? List.of() : sourceRows;
		long requestCount = rows.size();
		long successCount = rows.stream().filter(row -> STATUS_SUCCESS.equals(row.getStatus())).count();
		long failedCount = rows.stream().filter(row -> STATUS_FAILED.equals(row.getStatus())).count();
		long totalTokens = sum(rows, AgentTokenUsage::getTotalTokens);
		long promptTokens = sum(rows, AgentTokenUsage::getPromptTokens);
		long completionTokens = sum(rows, AgentTokenUsage::getCompletionTokens);
		long actualTokens = rows.stream()
			.filter(row -> METERING_ACTUAL.equals(row.getMeteringMode()))
			.mapToLong(row -> value(row.getTotalTokens()))
			.sum();
		long estimatedTokens = rows.stream()
			.filter(row -> METERING_ESTIMATED.equals(row.getMeteringMode()))
			.mapToLong(row -> value(row.getTotalTokens()))
			.sum();
		long unknownCount = rows.stream().filter(row -> METERING_UNKNOWN.equals(row.getMeteringMode())).count();
		long blockedCount = rows.stream().filter(row -> contains(row.getErrorCode(), "quota")
				|| contains(row.getErrorCode(), "rate") || contains(row.getErrorMessage(), "agent_runtime_quota")
				|| contains(row.getErrorMessage(), "agent_runtime_rate")).count();
		double averageTokens = requestCount == 0L ? 0D : totalTokens / (double) requestCount;
		return AgentTokenUsageSummaryResp.builder()
			.requestCount(requestCount)
			.successCount(successCount)
			.failedCount(failedCount)
			.totalTokens(totalTokens)
			.promptTokens(promptTokens)
			.completionTokens(completionTokens)
			.actualTokens(actualTokens)
			.estimatedTokens(estimatedTokens)
			.unknownCount(unknownCount)
			.blockedCount(blockedCount)
			.averageTokens(averageTokens)
			.build();
	}

	/**
	 * 查询AgentToken用量。
	 */
	public List<AgentTokenUsageBreakdownResp> queryBreakdown(AgentTokenUsageQueryReq request) {
		String groupBy = request == null || !StringUtils.hasText(request.getGroupBy()) ? "DAY"
				: request.getGroupBy().trim().toUpperCase();
		return toBreakdowns(selectAuthorizedUsageList(request), groupBy);
	}

	private List<AgentTokenUsageBreakdownResp> toBreakdowns(List<AgentTokenUsage> sourceRows, String groupBy) {
		Map<String, List<AgentTokenUsage>> grouped = new LinkedHashMap<>();
		for (AgentTokenUsage row : sourceRows == null ? List.<AgentTokenUsage>of() : sourceRows) {
			grouped.computeIfAbsent(groupKey(row, groupBy), ignored -> new java.util.ArrayList<>()).add(row);
		}
		List<AgentTokenUsageBreakdownResp> rows = grouped.entrySet()
			.stream()
			.map(entry -> toBreakdown(groupBy, entry.getKey(), entry.getValue()))
			.toList();
		if ("DAY".equals(groupBy)) {
			return rows.stream().sorted(java.util.Comparator.comparing(AgentTokenUsageBreakdownResp::groupKey)).toList();
		}
		return rows.stream()
			.sorted((left, right) -> Long.compare(value(right.totalTokens()), value(left.totalTokens())))
			.toList();
	}

	private AgentTokenUsageQueryReq copyOverviewQuery(AgentTokenUsageQueryReq request) {
		AgentTokenUsageQueryReq query = new AgentTokenUsageQueryReq();
		if (request == null) {
			return query;
		}
		query.setTenantId(request.getTenantId());
		query.setUserId(request.getUserId());
		query.setAgentId(request.getAgentId());
		query.setModelConfigId(request.getModelConfigId());
		query.setProvider(request.getProvider());
		query.setModelName(request.getModelName());
		query.setUsageSource(request.getUsageSource());
		query.setMeteringMode(request.getMeteringMode());
		query.setStatus(request.getStatus());
		query.setRequestSource(request.getRequestSource());
		query.setRuntimeRequestId(request.getRuntimeRequestId());
		query.setRootRuntimeRequestId(request.getRootRuntimeRequestId());
		query.setStartTime(request.getStartTime());
		query.setEndTime(request.getEndTime());
		return query;
	}

	/**
	 * 查询AgentToken用量。
	 */
	public IPage<AgentTokenUsage> queryDetailsPage(AgentTokenUsageQueryReq request) {
		AgentTokenUsageQueryReq query = request == null ? new AgentTokenUsageQueryReq() : request;
		return usageMapper.selectUsagePage(query.buildPage(), query, resolveAuthorizedTenantId(query));
	}

	private List<AgentTokenUsage> selectAuthorizedUsageList(AgentTokenUsageQueryReq request) {
		AgentTokenUsageQueryReq query = request == null ? new AgentTokenUsageQueryReq() : request;
		return usageMapper.selectUsageList(query, resolveAuthorizedTenantId(query));
	}

	/**
	 * 解析本次查询真正生效的租户：一律当前登录租户。请求体自带的 {@code tenantId} 只作误传忽略，
	 * 平台管理员切租户后也只看当前租户，不再走全平台查询。
	 */
	private String resolveAuthorizedTenantId(AgentTokenUsageQueryReq query) {
		String requestedTenantId = query.getTenantId() == null ? null : query.getTenantId().trim();
		query.setTenantId(null);
		String currentTenantId = platformScopePermissionService.requireCurrentTenantId("Token 用量");
		if (StringUtils.hasText(requestedTenantId) && !currentTenantId.equals(requestedTenantId)) {
			log.warn("请求体中的租户 ID 已被忽略, 按当前租户查询 Token 用量. requestedTenantId={}, tenantId={}",
					requestedTenantId, currentTenantId);
		}
		return currentTenantId;
	}

	/**
	 * 处理AgentToken用量。
	 */
	public void refreshDailyAggregation() {
		log.info("Agent token usage day aggregation is maintained incrementally.");
	}

	private void insertUsage(AgentTokenUsageContext context, long promptTokens, long completionTokens, long totalTokens,
			String meteringMode, String status, Long durationMs, String errorCode, String errorMessage, String rawUsageJson) {
		if (context == null) {
			return;
		}
		Instant now = Instant.now();
		AgentTokenUsage usage = AgentTokenUsage.builder()
			.tenantId(context.tenantId())
			.tenantCode(context.tenantCode())
			.userId(context.userId())
			.userNickName(context.userNickName())
			.clientId(context.clientId())
			.teamIdsJson(context.teamIdsJson())
			.agentId(context.agentId())
			.agentName(context.agentName())
			.sessionId(context.sessionId())
			.threadId(context.threadId())
			.runtimeRequestId(context.runtimeRequestId())
			.rootRuntimeRequestId(firstText(context.rootRuntimeRequestId(), context.runtimeRequestId()))
			.parentRuntimeRequestId(context.parentRuntimeRequestId())
			.orchestrationRunId(context.orchestrationRunId())
			.orchestrationStepId(context.orchestrationStepId())
			.modelConfigId(context.modelConfigId())
			.provider(context.provider())
			.modelName(context.modelName())
			.modelType(context.modelType())
			.usageSource(context.usageSource())
			.promptTokens(promptTokens)
			.completionTokens(completionTokens)
			.totalTokens(totalTokens)
			.meteringMode(meteringMode)
			.status(status)
			.durationMs(durationMs)
			.errorCode(errorCode)
			.errorMessage(errorMessage)
			.requestSource(context.requestSource())
			.cacheHit(Boolean.TRUE.equals(context.cacheHit()))
			.rawUsageJson(rawUsageJson)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		usageMapper.insert(usage);
		upsertDay(usage);
		updateTurnSnapshot(usage);
	}

	private void upsertDay(AgentTokenUsage usage) {
		Instant createTime = usage.getCreateTime() == null ? Instant.now() : usage.getCreateTime();
		AgentTokenUsageDay day = AgentTokenUsageDay.builder()
			.statDate(LocalDate.ofInstant(createTime, DAY_ZONE))
			.tenantId(defaultText(usage.getTenantId()))
			.userId(defaultText(usage.getUserId()))
			.userNickName(usage.getUserNickName())
			.agentId(defaultId(usage.getAgentId()))
			.agentName(usage.getAgentName())
			.modelConfigId(defaultId(usage.getModelConfigId()))
			.provider(defaultText(usage.getProvider()))
			.modelName(defaultText(usage.getModelName()))
			.usageSource(defaultText(usage.getUsageSource()))
			.requestCount(1L)
			.successCount(STATUS_SUCCESS.equals(usage.getStatus()) ? 1L : 0L)
			.failedCount(STATUS_FAILED.equals(usage.getStatus()) ? 1L : 0L)
			.promptTokens(value(usage.getPromptTokens()))
			.completionTokens(value(usage.getCompletionTokens()))
			.totalTokens(value(usage.getTotalTokens()))
			.actualTokens(METERING_ACTUAL.equals(usage.getMeteringMode()) ? value(usage.getTotalTokens()) : 0L)
			.estimatedTokens(METERING_ESTIMATED.equals(usage.getMeteringMode()) ? value(usage.getTotalTokens()) : 0L)
			.unknownCount(METERING_UNKNOWN.equals(usage.getMeteringMode()) ? 1L : 0L)
			.createTime(createTime)
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
		usageDayMapper.upsertDelta(day);
	}

	private void updateTurnSnapshot(AgentTokenUsage usage) {
		if (usage.getSessionId() == null || !StringUtils.hasText(usage.getRuntimeRequestId())) {
			return;
		}
		DataChatTurn turn = turnMapper.findBySessionIdAndRuntimeRequestId(usage.getSessionId(),
				usage.getRuntimeRequestId());
		if (turn == null) {
			return;
		}
		turn.setModelConfigId(firstNonNull(turn.getModelConfigId(), usage.getModelConfigId()));
		turn.setModelName(firstText(turn.getModelName(), usage.getModelName()));
		turn.setPromptTokens(value(turn.getPromptTokens()) + value(usage.getPromptTokens()));
		turn.setCompletionTokens(value(turn.getCompletionTokens()) + value(usage.getCompletionTokens()));
		turn.setTotalTokens(value(turn.getTotalTokens()) + value(usage.getTotalTokens()));
		turnMapper.updateById(turn);
	}

	private AgentTokenUsageBreakdownResp toBreakdown(String groupBy, String key, List<AgentTokenUsage> rows) {
		return AgentTokenUsageBreakdownResp.builder()
			.groupKey(key)
			.groupName(groupName(groupBy, key, rows))
			.requestCount((long) rows.size())
			.totalTokens(sum(rows, AgentTokenUsage::getTotalTokens))
			.promptTokens(sum(rows, AgentTokenUsage::getPromptTokens))
			.completionTokens(sum(rows, AgentTokenUsage::getCompletionTokens))
			.actualTokens(rows.stream()
				.filter(row -> METERING_ACTUAL.equals(row.getMeteringMode()))
				.mapToLong(row -> value(row.getTotalTokens()))
				.sum())
			.estimatedTokens(rows.stream()
				.filter(row -> METERING_ESTIMATED.equals(row.getMeteringMode()))
				.mapToLong(row -> value(row.getTotalTokens()))
				.sum())
			.unknownCount(rows.stream().filter(row -> METERING_UNKNOWN.equals(row.getMeteringMode())).count())
			.build();
	}

	private String groupName(String groupBy, String key, List<AgentTokenUsage> rows) {
		if ("USER".equals(groupBy)) {
			return firstSnapshot(rows, AgentTokenUsage::getUserNickName, key);
		}
		if ("AGENT".equals(groupBy)) {
			return firstSnapshot(rows, AgentTokenUsage::getAgentName, key);
		}
		return key;
	}

	private String firstSnapshot(List<AgentTokenUsage> rows, java.util.function.Function<AgentTokenUsage, String> mapper,
			String fallback) {
		if (rows != null) {
			for (AgentTokenUsage row : rows) {
				String value = mapper.apply(row);
				if (StringUtils.hasText(value)) {
					return value.trim();
				}
			}
		}
		return fallback;
	}

	private String groupKey(AgentTokenUsage usage, String groupBy) {
		return switch (groupBy) {
			case "USER" -> defaultText(usage.getUserId());
			case "AGENT" -> usage.getAgentId() == null ? "" : String.valueOf(usage.getAgentId());
			case "MODEL" -> firstText(usage.getModelName(), usage.getModelConfigId() == null ? "" : String.valueOf(usage.getModelConfigId()));
			case "TENANT" -> defaultText(usage.getTenantId());
			case "SOURCE" -> defaultText(usage.getUsageSource());
			default -> usage.getCreateTime() == null ? "" : LocalDate.ofInstant(usage.getCreateTime(), DAY_ZONE).toString();
		};
	}

	/**
	 * 处理AgentToken用量。
	 */
	public long estimateTokens(String text) {
		if (!StringUtils.hasText(text)) {
			return 1L;
		}
		return Math.max(1L, (long) Math.ceil(text.length() / 2D));
	}

	private String extractText(ChatResponse response) {
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
			return "";
		}
		String text = response.getResult().getOutput().getText();
		return text == null ? "" : text;
	}

	private AgentStructuredModelCallResult structuredResult(ChatResponse response, String finishReason,
			long promptTokens, long completionTokens, long totalTokens, String meteringMode, long durationMs) {
		return new AgentStructuredModelCallResult(response, extractText(response), extractToolCalls(response),
				finishReason, promptTokens, completionTokens, totalTokens, meteringMode, durationMs);
	}

	private List<AssistantMessage.ToolCall> extractToolCalls(ChatResponse response) {
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null
				|| response.getResult().getOutput().getToolCalls() == null) {
			return List.of();
		}
		return response.getResult().getOutput().getToolCalls();
	}

	private String finishReason(ChatResponse response) {
		return response == null || response.getResult() == null || response.getResult().getMetadata() == null
				? null : response.getResult().getMetadata().getFinishReason();
	}

	private String rawUsage(long promptTokens, long completionTokens, long totalTokens, String finishReason) {
		try {
			Map<String, Object> rawUsage = new LinkedHashMap<>();
			rawUsage.put("promptTokens", promptTokens);
			rawUsage.put("completionTokens", completionTokens);
			rawUsage.put("totalTokens", totalTokens);
			if (StringUtils.hasText(finishReason)) {
				rawUsage.put("finishReason", finishReason);
			}
			return objectMapper.writeValueAsString(rawUsage);
		}
		catch (Exception ex) {
			log.warn("Failed to serialize the raw token usage, it will be persisted as null. totalTokens={}", totalTokens,
					ex);
			return null;
		}
	}

	private String writeTeamIds(List<String> teamIds) {
		if (teamIds == null || teamIds.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(teamIds);
		}
		catch (Exception ex) {
			log.warn("Failed to serialize team ids for the token usage record, they will be persisted as null. count={}",
					teamIds.size(), ex);
			return null;
		}
	}

	private long token(Number value) {
		return value == null ? 0L : value.longValue();
	}

	private long sum(List<AgentTokenUsage> rows, java.util.function.Function<AgentTokenUsage, Long> mapper) {
		return rows.stream().map(mapper).filter(Objects::nonNull).mapToLong(Long::longValue).sum();
	}

	private long value(Long value) {
		return value == null ? 0L : value;
	}

	private Long defaultId(Long value) {
		return value == null ? 0L : value;
	}

	private Long firstNonNull(Long first, Long second) {
		return first != null ? first : second;
	}

	private String defaultText(String value) {
		return StringUtils.hasText(value) ? value.trim() : "";
	}

	private String firstText(String first, String second) {
		return StringUtils.hasText(first) ? first : second;
	}

	private boolean contains(String source, String pattern) {
		return StringUtils.hasText(source) && source.contains(pattern);
	}

	private String errorCode(Throwable error) {
		if (error == null) {
			return null;
		}
		return AgentRuntimeErrorClassifier.classify(error).code().getValue();
	}

	private Long elapsedMs(long startNanos) {
		return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}

}
