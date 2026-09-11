/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.dto.ClarificationResponse;
import com.sn68.agent.dataagent.entity.DataAgentRoutePending;
import com.sn68.agent.dataagent.repository.DataAgentRoutePendingMapper;
import com.sn68.agent.dataagent.repository.RoutePendingStatus;
import com.sn68.agent.dataagent.routing.model.ExplicitRouteTarget;
import com.sn68.agent.dataagent.routing.model.RouteClarification;
import com.sn68.agent.dataagent.routing.model.RouteClarificationOption;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.service.routing.RoutePendingService.OrchestrationExecutionPolicy;
import com.sn68.agent.dataagent.linking.LinkKeysMarker;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 路由待确认交互服务：负责澄清/确认类交互的创建（含选项与快照持久化）、一次性消费、
 * 执行状态回写与过期清理。交互令牌以 SHA-256 摘要落库，明文仅返回给调用方。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutePendingServiceImpl implements RoutePendingService {

	private static final Duration TTL = Duration.ofMinutes(30);

	private static final int MAX_OPTIONS = 8;

	private static final int MAX_TEXT = 2000;

	private static final int MAX_CLARIFICATION_ROUND = 2;

	private static final int MAX_OPTION_LABEL = 300;

	private static final int MAX_PROMPT = 1000;

	private final DataAgentRoutePendingMapper pendingMapper;

	private final ObjectMapper objectMapper;

	@Scheduled(fixedDelayString = "${spring.ai.agent.routing.pending-expiry-scan-interval:60s}")
	public void expirePending() {
		int expired = expirePending(Instant.now());
		if (expired > 0) {
			log.info("Marked expired route pending interactions. count={}", expired);
		}
	}

	int expirePending(Instant now) {
		return pendingMapper.expirePending(now);
	}

	@Override
	public PendingInteraction create(AgentRequest request, String interactionType, String originalQuery, int round,
			RouteClarification clarification, RouteDecision routeSnapshot) {
		return createInternal(request, interactionType, originalQuery, round, clarification, routeSnapshot);
	}

	private PendingInteraction createInternal(AgentRequest request, String interactionType, String originalQuery, int round,
			RouteClarification clarification, RouteDecision routeSnapshot) {
		if (request == null || clarification == null || !StringUtils.hasText(interactionType)) {
			throw CheckedException.badRequest("Pending interaction context is invalid");
		}
		validateInteractionType(interactionType);
		validateBinding(request);
		validateRound(interactionType, round);
		PendingBinding binding = resolveBinding(request);
		boolean orchestrationChild = request.isCollaboratorChild();
		String token = UUID.randomUUID() + "." + UUID.randomUUID();
		Instant now = Instant.now();
		Instant expiresAt = now.plus(TTL);
		OptionPayloads optionPayloads = buildOptionPayloads(interactionType, clarification);
		List<Map<String, Object>> options = optionPayloads.publicOptions();
		Map<String, Map<String, Object>> internalOptions = optionPayloads.internalOptions();
		Map<String, Object> publicPayload = new LinkedHashMap<>();
		publicPayload.put("options", options);
		publicPayload.put("allowFreeText", clarification.allowFreeText());
		publicPayload.put("schemaVersion", schemaVersion(interactionType));
		if (TYPE_CONFIRMATION.equals(interactionType)) {
			applyConfirmationPresentation(publicPayload, clarification, routeSnapshot);
		}
		Map<String, Object> internalPayload = new LinkedHashMap<>();
		internalPayload.put("options", internalOptions);
		internalPayload.put("routeSnapshot", routeSnapshot);
		internalPayload.put("effectiveRoutingQuery", effectiveRoutingQuery(request, originalQuery));
		if (orchestrationChild) {
			internalPayload.put("orchestrationContinuation", continuationSnapshot(request));
		}
		DataAgentRoutePending pending = DataAgentRoutePending.builder()
			.tenantId(binding.tenantId())
			.ownerAgentId(binding.ownerAgentId())
			.sessionId(parseLong(binding.threadId()))
			.userId(userKey(request))
			.threadId(binding.threadId())
			.runtimeRequestId(request.getRuntimeRequestId())
			.parentRunId(orchestrationChild ? request.getOrchestrationRunId() : null)
			.parentStepId(orchestrationChild ? request.getOrchestrationStepId() : null)
			.executionState(EXECUTION_PENDING)
			.tokenHash(SecureUtil.sha256(token))
			.interactionType(interactionType)
			.status(RoutePendingStatus.PENDING)
			.clarificationRound(round)
			.originalQuery(bounded(originalQuery, MAX_TEXT))
			.prompt(bounded(clarification.prompt(), MAX_PROMPT))
			.publicPayload(writeJson(publicPayload))
			.routeSnapshot(writeJson(internalPayload))
			.expiresAt(expiresAt)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		pendingMapper.insert(pending);
		Map<String, Object> metadata = buildInteractionMetadata(interactionType, clarification, publicPayload, options,
				token, expiresAt);
		return new PendingInteraction(token, Map.copyOf(metadata));
	}

	/**
	 * 构建交互选项的双份载荷：对外展示选项（id/label/description）与内部映射
	 * （label/value/selection，供消费时还原用户选择），上限 {@link #MAX_OPTIONS} 条。
	 */
	private OptionPayloads buildOptionPayloads(String interactionType, RouteClarification clarification) {
		List<Map<String, Object>> options = new ArrayList<>();
		Map<String, Map<String, Object>> internalOptions = new LinkedHashMap<>();
		int index = 0;
		for (RouteClarificationOption option : clarification.options() == null ? List.<RouteClarificationOption>of()
				: clarification.options()) {
			if (option == null || !StringUtils.hasText(option.label()) || index >= MAX_OPTIONS) {
				continue;
			}
			String optionId = TYPE_CONFIRMATION.equals(interactionType) && StringUtils.hasText(option.optionId())
					? option.optionId().trim() : "o" + (++index);
			Map<String, Object> publicOption = new LinkedHashMap<>();
			publicOption.put("id", optionId);
			publicOption.put("label", bounded(option.label(), MAX_OPTION_LABEL));
			if (StringUtils.hasText(option.value())) {
				publicOption.put("description", bounded(option.value(), 500));
			}
			options.add(publicOption);
			Map<String, Object> mapping = new LinkedHashMap<>();
			mapping.put("label", bounded(option.label(), MAX_OPTION_LABEL));
			mapping.put("value", bounded(option.value(), 1000));
			mapping.put("selection", option.selection());
			internalOptions.put(optionId, mapping);
		}
		return new OptionPayloads(options, internalOptions);
	}

	/**
	 * 组装返回给调用方的交互元数据（含明文令牌与选项展示信息）；确认类交互附带风险级别与计划摘要。
	 */
	private Map<String, Object> buildInteractionMetadata(String interactionType, RouteClarification clarification,
			Map<String, Object> publicPayload, List<Map<String, Object>> options, String token, Instant expiresAt) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("schemaVersion", publicPayload.get("schemaVersion"));
		metadata.put("clarificationId", token);
		metadata.put("title", clarification.title());
		metadata.put("prompt", bounded(clarification.prompt(), MAX_PROMPT));
		metadata.put("options", options);
		metadata.put("allowFreeText", clarification.allowFreeText());
		if (TYPE_CONFIRMATION.equals(interactionType)) {
			metadata.put("riskLevel", clarification.riskLevel());
			copyIfPresent(publicPayload, metadata, "summary");
			copyIfPresent(publicPayload, metadata, "planSteps");
		}
		metadata.put("expiresAt", expiresAt.toString());
		return metadata;
	}

	/**
	 * 交互选项的双份载荷：对外展示选项与内部消费映射。
	 */
	private record OptionPayloads(List<Map<String, Object>> publicOptions,
			Map<String, Map<String, Object>> internalOptions) {
	}

	@Override
	public PendingResolution consume(AgentRequest request) {
		if (request == null) {
			return null;
		}
		Instant now = Instant.now();
		var response = request.getClarificationResponse();
		if (response == null) {
			return consumeWithoutToken(request, now);
		}
		if (!StringUtils.hasText(response.getClarificationId()) || !StringUtils.hasText(response.getSchemaVersion())) {
			throw CheckedException.badRequest("Clarification response is incomplete");
		}
		DataAgentRoutePending pending = pendingMapper.findActive(SecureUtil.sha256(response.getClarificationId()), now);
		if (pending == null || !matchesBinding(request, pending)) {
			throw CheckedException.badRequest("Clarification has expired or is not valid for this session");
		}
		return consumeResolved(request, pending, response, now);
	}

	private PendingResolution consumeWithoutToken(AgentRequest request, Instant now) {
		PendingBinding binding = resolveBinding(request);
		if (!StringUtils.hasText(binding.tenantId()) || binding.ownerAgentId() == null
				|| !StringUtils.hasText(binding.threadId()) || !StringUtils.hasText(userKey(request))) {
			return null;
		}
		DataAgentRoutePending pending = pendingMapper.findLatestPending(binding.tenantId(), binding.ownerAgentId(),
				userKey(request), binding.threadId(), now);
		if (pending == null) {
			return null;
		}
		String optionId;
		try {
			optionId = matchQueryToOption(request.getQuery(), pending);
		}
		catch (RuntimeException ex) {
			log.warn("Pending option alignment failed, abandoning pending. threadId={}, runtimeRequestId={}",
					binding.threadId(), request.getRuntimeRequestId(), ex);
			optionId = null;
		}
		if (!StringUtils.hasText(optionId)) {
			pendingMapper.cancelPending(pending.getId(), now);
			log.info(
					"Abandoned pending clarification because query did not match an option. tenantId={}, threadId={}, runtimeRequestId={}",
					binding.tenantId(), binding.threadId(), request.getRuntimeRequestId());
			return null;
		}
		ClarificationResponse synthesized = new ClarificationResponse(schemaVersion(pending.getInteractionType()),
				"query-aligned", List.of(optionId), null);
		request.setClarificationResponse(synthesized);
		return consumeResolved(request, pending, synthesized, now);
	}

	private String matchQueryToOption(String query, DataAgentRoutePending pending) {
		if (!StringUtils.hasText(query) || pending == null) {
			return null;
		}
		String normalized = query.trim();
		Map<String, Map<String, Object>> options = optionMappings(readMap(pending.getRouteSnapshot()).get("options"));
		if (options.isEmpty()) {
			return null;
		}
		int index = 0;
		String ordinalMatch = null;
		for (Map.Entry<String, Map<String, Object>> entry : options.entrySet()) {
			index++;
			Map<String, Object> mapping = entry.getValue();
			if (mapping == null) {
				continue;
			}
			if (equalsIgnoreCaseText(normalized, textValue(mapping.get("label")))
					|| equalsIgnoreCaseText(normalized, textValue(mapping.get("value")))) {
				return entry.getKey();
			}
			if (normalized.equals(String.valueOf(index))) {
				ordinalMatch = entry.getKey();
			}
		}
		return ordinalMatch;
	}

	private PendingResolution consumeResolved(AgentRequest request, DataAgentRoutePending pending,
			ClarificationResponse response, Instant now) {
		validateInteractionType(pending.getInteractionType());
		String expectedSchema = schemaVersion(pending.getInteractionType());
		if (!expectedSchema.equals(response.getSchemaVersion())) {
			throw CheckedException.badRequest("Clarification schema version is invalid");
		}
		Map<String, Object> internal = readMap(pending.getRouteSnapshot());
		OrchestrationContinuation continuation = readOrchestrationContinuation(internal);
		Map<String, Map<String, Object>> options = optionMappings(internal.get("options"));
		List<String> submittedOptionIds = response.getOptionIds() == null ? List.of() : response.getOptionIds().stream()
				.filter(StringUtils::hasText).limit(MAX_OPTIONS + 1).toList();
		List<String> optionIds = submittedOptionIds.stream().distinct().limit(MAX_OPTIONS).toList();
		if (TYPE_CONFIRMATION.equals(pending.getInteractionType())) {
			if (optionIds.size() != 1 || !("confirm".equalsIgnoreCase(optionIds.get(0))
					|| "cancel".equalsIgnoreCase(optionIds.get(0)))) {
				throw CheckedException.badRequest("Confirmation option is invalid");
			}
			if ("cancel".equalsIgnoreCase(optionIds.get(0))) {
				consumeOnce(pending, request, now);
				return new PendingResolution(pending.getInteractionType(), pending.getOriginalQuery(),
						pending.getOriginalQuery(), safeRound(pending), null, true, continuation);
			}
			RouteDecision decision = readRouteSnapshot(internal.get("routeSnapshot"));
			if (decision == null) {
				throw CheckedException.badRequest("Confirmed route is no longer available");
			}
			consumeOnce(pending, request, now);
			if (continuation != null) {
				return new PendingResolution(pending.getInteractionType(), pending.getOriginalQuery(),
						pending.getOriginalQuery(), safeRound(pending), null, false,
						continuation.withResolution(pending.getOriginalQuery(), decision));
			}
			return new PendingResolution(pending.getInteractionType(), pending.getOriginalQuery(),
					pending.getOriginalQuery(), safeRound(pending), decision, false);
		}
		List<String> supplements = new ArrayList<>();
		List<RouteSelection> selectedRoutes = new ArrayList<>();
		for (String optionId : optionIds) {
			Map<String, Object> mapping = options.get(optionId);
			if (mapping == null) {
				throw CheckedException.badRequest("Clarification option is invalid");
			}
			RouteSelection selection = readSelection(mapping.get("selection"));
			if (selection != null) {
				selectedRoutes.add(selection);
			}
			supplements.add(bounded(String.valueOf(mapping.getOrDefault("value", mapping.get("label"))), MAX_TEXT));
		}
		if (StringUtils.hasText(response.getFreeText())) {
			supplements.add(bounded(response.getFreeText(), MAX_TEXT));
		}
		if (supplements.isEmpty() && selectedRoutes.isEmpty()) {
			throw CheckedException.badRequest("Please provide a clarification response");
		}
		String original = bounded(pending.getOriginalQuery(), MAX_TEXT);
		if (continuation == null && TYPE_ROUTE_CLARIFICATION.equals(pending.getInteractionType())
				&& selectedRoutes.size() == 1 && optionIds.size() == 1) {
			String effective = original;
			if (StringUtils.hasText(response.getFreeText())) {
				effective = (StringUtils.hasText(original) ? original + "\n" : "")
						+ bounded(response.getFreeText(), MAX_TEXT);
			}
			consumeOnce(pending, request, now);
			request.setExplicitRouteTarget(toExplicitTarget(selectedRoutes.get(0)));
			applyResolvedQuery(request, original, effective, pending);
			return new PendingResolution(pending.getInteractionType(), original, effective, safeRound(pending), null, false);
		}
		String effective = (StringUtils.hasText(original) ? original + "\n" : "")
				+ "补充业务信息：" + String.join("；", supplements);
		consumeOnce(pending, request, now);
		if (continuation != null) {
			return new PendingResolution(pending.getInteractionType(), original, effective, safeRound(pending), null, false,
					continuation.withResolution(effective, null));
		}
		applyResolvedQuery(request, original, effective, pending);
		return new PendingResolution(pending.getInteractionType(), original, effective, safeRound(pending), null, false);
	}

	private void applyResolvedQuery(AgentRequest request, String original, String effective,
			DataAgentRoutePending pending) {
		request.setOriginalQuerySnapshot(original);
		request.setEffectiveRoutingQuery(effective);
		request.setRouteClarificationRound(safeRound(pending));
		request.setQuery(effective);
	}

	private RouteSelection readSelection(Object value) {
		if (value == null) {
			return null;
		}
		try {
			RouteSelection selection = objectMapper.convertValue(value, RouteSelection.class);
			if (selection == null || selection.target() == null || selection.target().targetType() == null
					|| selection.target().targetId() == null || selection.routeProfileId() == null) {
				return null;
			}
			return selection;
		}
		catch (IllegalArgumentException ex) {
			log.warn("Pending clarification selection could not be restored");
			return null;
		}
	}

	private ExplicitRouteTarget toExplicitTarget(RouteSelection selection) {
		RouteTargetRef target = selection.target();
		return new ExplicitRouteTarget(target.targetType(), target.targetId(), target.targetVersionId(),
				selection.routeArtifactId(), selection.routeProfileId());
	}

	@Override
	public void finishExecution(AgentRequest request, String executionState, String resultReference) {
		if (request == null || !isTerminalExecutionState(executionState)) {
			return;
		}
		PendingBinding binding = resolveBinding(request);
		pendingMapper.finishClaimed(binding.tenantId(), binding.ownerAgentId(), userKey(request), binding.threadId(),
				request.getRuntimeRequestId(), executionState, bounded(resultReference, 500), Instant.now());
	}

	private void consumeOnce(DataAgentRoutePending pending, AgentRequest request, Instant now) {
		if (pendingMapper.consume(pending, request.getRuntimeRequestId(), now) != 1) {
			throw CheckedException.badRequest("Clarification has already been consumed");
		}
	}

	private boolean matchesBinding(AgentRequest request, DataAgentRoutePending pending) {
		return equalsText(request.getTenantIdSnapshot(), pending.getTenantId())
				&& equalsText(userKey(request), pending.getUserId())
				&& equalsLong(parseLong(request.getAgentId()), pending.getOwnerAgentId())
				&& equalsText(request.getThreadId(), pending.getThreadId());
	}

	private OrchestrationContinuation readOrchestrationContinuation(Map<String, Object> internal) {
		Object value = internal == null ? null : internal.get("orchestrationContinuation");
		if (!(value instanceof Map<?, ?> source)) {
			return null;
		}
		Long runId = parseLong(source.get("runId"));
		Long stepId = parseLong(source.get("stepId"));
		String childAgentId = textValue(source.get("childAgentId"));
		String childThreadId = textValue(source.get("childThreadId"));
		String childRuntimeRequestId = textValue(source.get("childRuntimeRequestId"));
		String childQuery = textValue(source.get("childQuery"));
		String childDelegationMode = textValue(source.get("childDelegationMode"));
		Map<String, Object> childDependencyInputs = readObjectMap(source.get("childDependencyInputs"));
		RouteDecision orchestrationRouteSnapshot = readRouteSnapshot(source.get("orchestrationRouteSnapshot"));
		OrchestrationExecutionPolicy executionPolicy = readExecutionPolicy(
				source.get("orchestrationExecutionPolicy"));
		if (runId == null || stepId == null || !StringUtils.hasText(childAgentId)
				|| !StringUtils.hasText(childThreadId) || !StringUtils.hasText(childQuery)) {
			throw CheckedException.badRequest("Orchestration continuation state is invalid");
		}
		return new OrchestrationContinuation(runId, stepId, childAgentId, childThreadId, childRuntimeRequestId,
				childQuery, childDependencyInputs, null, orchestrationRouteSnapshot, childDelegationMode, executionPolicy);
	}

	private OrchestrationExecutionPolicy readExecutionPolicy(Object value) {
		Map<String, Object> source = readObjectMap(value);
		Object exposeTrace = source.get("exposeTrace");
		if (!(exposeTrace instanceof Boolean enabled)) {
			return null;
		}
		return new OrchestrationExecutionPolicy(textValue(source.get("failureStrategy")), enabled);
	}

	private Map<String, Object> continuationSnapshot(AgentRequest request) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("runId", request.getOrchestrationRunId());
		snapshot.put("stepId", request.getOrchestrationStepId());
		snapshot.put("childAgentId", request.getAgentId());
		snapshot.put("childThreadId", request.getThreadId());
		snapshot.put("childRuntimeRequestId", request.getRuntimeRequestId());
		snapshot.put("childQuery", request.getQuery());
		snapshot.put("childDelegationMode", request.getCollaboratorDelegationMode());
		snapshot.put("childDependencyInputs", request.getOrchestrationDependencyInputs());
		snapshot.put("orchestrationRouteSnapshot", request.getOrchestrationRouteSnapshot());
		Map<String, Object> executionPolicy = new LinkedHashMap<>();
		executionPolicy.put("failureStrategy", request.getOrchestrationFailureStrategy());
		executionPolicy.put("exposeTrace", Boolean.TRUE.equals(request.getOrchestrationExposeTrace()));
		snapshot.put("orchestrationExecutionPolicy", executionPolicy);
		return snapshot;
	}

	private Map<String, Map<String, Object>> optionMappings(Object value) {
		if (!(value instanceof Map<?, ?> source)) {
			return Map.of();
		}
		Map<String, Map<String, Object>> result = new LinkedHashMap<>();
		source.forEach((key, item) -> {
			if (key != null && item instanceof Map<?, ?> map) {
				Map<String, Object> copy = new LinkedHashMap<>();
				map.forEach((mapKey, mapValue) -> copy.put(String.valueOf(mapKey), mapValue));
				result.put(String.valueOf(key), copy);
			}
		});
		return result;
	}

	private Map<String, Object> readObjectMap(Object value) {
		if (!(value instanceof Map<?, ?> source)) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		source.forEach((key, item) -> {
			if (key != null && item != null) {
				result.put(String.valueOf(key), item);
			}
		});
		return Map.copyOf(result);
	}

	private RouteDecision readRouteSnapshot(Object value) {
		try {
			return value == null ? null : objectMapper.convertValue(value, RouteDecision.class);
		}
		catch (IllegalArgumentException ex) {
			log.warn("Pending route snapshot could not be restored", ex);
			return null;
		}
	}

	private Map<String, Object> readMap(String json) {
		try {
			return StringUtils.hasText(json) ? objectMapper.readValue(json, new TypeReference<>() { }) : Map.of();
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Pending interaction state is invalid");
		}
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Pending interaction state could not be serialized", ex);
		}
	}

	private int safeRound(DataAgentRoutePending pending) {
		return pending.getClarificationRound() == null ? 0 : pending.getClarificationRound();
	}

	private Long parseLong(String value) {
		try {
			return StringUtils.hasText(value) ? Long.valueOf(value.trim()) : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private Long parseLong(Object value) {
		return value == null ? null : parseLong(String.valueOf(value));
	}

	private boolean equalsLong(Long left, Long right) {
		return left != null && left.equals(right);
	}

	private boolean equalsText(String left, String right) {
		return StringUtils.hasText(left) && StringUtils.hasText(right) && left.trim().equals(right.trim());
	}

	private String userKey(AgentRequest request) {
		return request != null && StringUtils.hasText(request.getUserIdSnapshot())
				? request.getUserIdSnapshot().trim() : null;
	}

	private void validateBinding(AgentRequest request) {
		PendingBinding binding = resolveBinding(request);
		if (!StringUtils.hasText(binding.tenantId()) || binding.ownerAgentId() == null
				|| !StringUtils.hasText(binding.threadId()) || !StringUtils.hasText(request.getUserIdSnapshot())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			throw CheckedException.badRequest("Pending interaction security binding is incomplete");
		}
	}

	private PendingBinding resolveBinding(AgentRequest request) {
		if (request != null && request.isCollaboratorChild()) {
			return new PendingBinding(request.getTenantIdSnapshot(), parseLong(request.getParentAgentId()),
					request.getParentThreadId());
		}
		return new PendingBinding(request == null ? null : request.getTenantIdSnapshot(),
				parseLong(request == null ? null : request.getAgentId()), request == null ? null : request.getThreadId());
	}

	private String textValue(Object value) {
		return value == null ? null : String.valueOf(value).trim();
	}

	private void validateRound(String interactionType, int round) {
		boolean clarification = TYPE_BUSINESS_CLARIFICATION.equals(interactionType)
				|| TYPE_ROUTE_CLARIFICATION.equals(interactionType);
		if (round < 0 || round > MAX_CLARIFICATION_ROUND || clarification && round == 0) {
			throw CheckedException.badRequest("Clarification round is invalid");
		}
	}

	private void validateInteractionType(String interactionType) {
		if (!TYPE_BUSINESS_CLARIFICATION.equals(interactionType) && !TYPE_ROUTE_CLARIFICATION.equals(interactionType)
		&& !TYPE_CONFIRMATION.equals(interactionType)) {
			throw CheckedException.badRequest("Pending interaction type is invalid");
		}
	}

	private boolean isTerminalExecutionState(String executionState) {
		return EXECUTION_SUCCEEDED.equals(executionState) || EXECUTION_FAILED.equals(executionState)
				|| EXECUTION_CANCELLED.equals(executionState) || EXECUTION_FORWARDED.equals(executionState);
	}

	private String schemaVersion(String interactionType) {
		if (TYPE_CONFIRMATION.equals(interactionType)) {
			return "confirm/v1";
		}
		return "business-clarify/v1";
	}

	private void applyConfirmationPresentation(Map<String, Object> publicPayload, RouteClarification clarification,
			RouteDecision routeSnapshot) {
		List<Map<String, Object>> steps = new ArrayList<>();
		List<RoutePlanStep> planSteps = routeSnapshot == null || routeSnapshot.plan() == null
				? List.of() : routeSnapshot.plan().steps();
		for (int index = 0; index < planSteps.size() && index < MAX_OPTIONS; index++) {
			RoutePlanStep step = planSteps.get(index);
			if (step == null) {
				continue;
			}
			String description = firstText(step.expectedOutput(), step.queryFragment());
			if (!StringUtils.hasText(description)) {
				continue;
			}
			Map<String, Object> visibleStep = new LinkedHashMap<>();
			visibleStep.put("name", "步骤 " + (index + 1));
			visibleStep.put("description", bounded(description, 500));
			visibleStep.put("riskLevel", bounded(clarification.riskLevel(), 40));
			steps.add(visibleStep);
		}
		String summary = StringUtils.hasText(clarification.prompt()) ? clarification.prompt()
				: steps.stream().map(step -> String.valueOf(step.get("description"))).collect(java.util.stream.Collectors.joining("；"));
		if (StringUtils.hasText(summary)) {
			publicPayload.put("summary", bounded(summary, 1000));
		}
		if (!steps.isEmpty()) {
			publicPayload.put("planSteps", List.copyOf(steps));
		}
	}

	private String effectiveRoutingQuery(AgentRequest request, String originalQuery) {
		String effective = request == null ? null : request.getEffectiveRoutingQuery();
		if (!StringUtils.hasText(effective) && request != null) {
			effective = request.getQuery();
		}
		String source = StringUtils.hasText(effective) ? effective : originalQuery;
		String stripped = LinkKeysMarker.strip(source);
		if (!StringUtils.hasText(stripped)) {
			stripped = LinkKeysMarker.strip(originalQuery);
		}
		return bounded(stripped, MAX_TEXT);
	}

	private boolean equalsIgnoreCaseText(String left, String right) {
		return StringUtils.hasText(left) && StringUtils.hasText(right) && left.trim().equalsIgnoreCase(right.trim());
	}

	private String effectiveRoutingQuery(Map<String, Object> internal, String fallback) {
		Object value = internal == null ? null : internal.get("effectiveRoutingQuery");
		String effective = value == null ? null : String.valueOf(value);
		return bounded(StringUtils.hasText(effective) ? effective : fallback, MAX_TEXT);
	}

	private String bounded(String value, int maxLength) {
		String text = value == null ? "" : value.trim();
		return text.length() <= maxLength ? text : text.substring(0, maxLength);
	}

	private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
		if (source.containsKey(key)) {
			target.put(key, source.get(key));
		}
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

	private record PendingBinding(String tenantId, Long ownerAgentId, String threadId) {
	}

}
