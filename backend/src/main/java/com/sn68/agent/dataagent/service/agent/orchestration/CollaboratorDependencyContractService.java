/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.agent.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.routing.RouteStageException;
import com.sn68.agent.dataagent.routing.model.RouteDependencyValueType;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RouteStepInputMapping;
import com.sn68.agent.dataagent.routing.model.RouteStepOutputBinding;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Resolves the server-owned data contract for dependent collaborator steps.
 *
 * <p>Published Skill schemas opt in with the visible JSON-Schema extension
 * {@code x-orchestration-transfer}. Its value is one of {@code ID}, {@code FILTER},
 * or {@code STATISTIC}. Input properties may additionally set
 * {@code x-orchestration-source} to {@code stepId.field}; otherwise exactly one
 * predecessor with the same field and type must exist.</p>
 */
@Service
@RequiredArgsConstructor
public class CollaboratorDependencyContractService {

	private static final String TRANSFER_EXTENSION = "x-orchestration-transfer";

	private static final String SOURCE_EXTENSION = "x-orchestration-source";

	private static final int MAX_TRANSFER_VALUE_CHARS = 4096;

	private final DataAgentSkillBindingMapper bindingMapper;

	private final DataAgentSkillMapper skillMapper;

	private final DataAgentSkillVersionMapper versionMapper;

	private final ObjectMapper objectMapper;

	public RoutePlan bind(RoutePlan plan, Map<RouteTargetRef, DataAgent> collaborators, String tenantId) {
		if (plan == null || plan.steps().isEmpty()) {
			return RoutePlan.empty();
		}
		if (plan.steps().stream().noneMatch(step -> !step.dependsOn().isEmpty())) {
			return plan;
		}
		Map<String, RoutePlanStep> stepsById = plan.steps().stream()
			.collect(Collectors.toMap(RoutePlanStep::stepId, item -> item, (left, right) -> left,
					LinkedHashMap::new));
		Map<String, PublishedContract> contracts = new LinkedHashMap<>();
		for (RoutePlanStep step : plan.steps()) {
			DataAgent collaborator = collaborators == null ? null : collaborators.get(step.target());
			if (collaborator == null || collaborator.getId() == null) {
				throw invalid("Route plan collaborator is no longer available");
			}
			contracts.put(step.stepId(), resolveContract(collaborator, tenantId));
		}

		Map<String, Map<String, RouteDependencyValueType>> outputs = new LinkedHashMap<>();
		Map<String, List<RouteStepInputMapping>> inputs = new LinkedHashMap<>();
		for (RoutePlanStep step : plan.steps()) {
			if (step.dependsOn().isEmpty()) {
				inputs.put(step.stepId(), List.of());
				continue;
			}
			PublishedContract target = contracts.get(step.stepId());
			if (target.inputs().isEmpty()) {
				inputs.put(step.stepId(), List.of());
				continue;
			}
			List<RouteStepInputMapping> mappings = new ArrayList<>();
			for (Map.Entry<String, InputField> entry : target.inputs().entrySet()) {
				SourceField source = resolveSource(step, entry.getKey(), entry.getValue(), contracts);
				mappings.add(new RouteStepInputMapping(source.stepId(), source.field(), entry.getKey(),
						entry.getValue().valueType()));
				outputs.computeIfAbsent(source.stepId(), ignored -> new LinkedHashMap<>())
					.put(source.field(), entry.getValue().valueType());
			}
			inputs.put(step.stepId(), List.copyOf(mappings));
		}

		List<RoutePlanStep> bound = plan.steps().stream().map(step -> new RoutePlanStep(step.stepId(), step.target(),
				step.queryFragment(), step.dependsOn(), step.expectedOutput(),
				outputBindings(outputs.get(step.stepId())), inputs.getOrDefault(step.stepId(), List.of()),
				step.delegationMode(), step.collaboratorAgentId())).toList();
		return new RoutePlan(bound);
	}

	public Map<String, Object> extractOutput(CollaboratorRoute route, String answer) {
		if (route == null || route.outputBindings().isEmpty()) {
			return Map.of();
		}
		JsonNode root;
		try {
			root = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(answer);
		}
		catch (Exception ex) {
			throw invalid("Collaborator result does not satisfy the published output contract");
		}
		if (root == null || !root.isObject()) {
			throw invalid("Collaborator result does not satisfy the published output contract");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (RouteStepOutputBinding binding : route.outputBindings()) {
			JsonNode value = root.get(binding.field());
			if (value == null || value.isNull() || !matches(binding.valueType(), value)) {
				throw invalid("Collaborator result is missing a published output field: " + binding.field());
			}
			try {
				Object converted = objectMapper.convertValue(value, Object.class);
				if (objectMapper.writeValueAsString(converted).length() > MAX_TRANSFER_VALUE_CHARS) {
					throw invalid("Collaborator output field exceeds the transfer limit: " + binding.field());
				}
				result.put(binding.field(), converted);
			}
			catch (RouteStageException ex) {
				throw ex;
			}
			catch (Exception ex) {
				throw invalid("Collaborator result does not satisfy the published output contract");
			}
		}
		return Map.copyOf(result);
	}

	public Map<String, Object> dependencyInputs(CollaboratorRoute route,
			List<CollaboratorExecutionResult> dependencyResults) {
		if (route == null || route.inputMappings().isEmpty()) {
			return Map.of();
		}
		Map<String, CollaboratorExecutionResult> results = (dependencyResults == null ? List.<CollaboratorExecutionResult>of()
				: dependencyResults).stream().filter(Objects::nonNull).filter(CollaboratorExecutionResult::success)
			.filter(result -> result.route() != null && StringUtils.hasText(result.route().stepId()))
			.collect(Collectors.toMap(result -> result.route().stepId(), item -> item, (left, right) -> left));
		Map<String, Object> inputs = new LinkedHashMap<>();
		for (RouteStepInputMapping mapping : route.inputMappings()) {
			CollaboratorExecutionResult source = results.get(mapping.sourceStepId());
			Object value = source == null ? null : source.structuredOutput().get(mapping.sourceField());
			if (!matches(mapping.valueType(), value)) {
				throw invalid("Published predecessor output is unavailable: " + mapping.sourceStepId() + "."
						+ mapping.sourceField());
			}
			inputs.put(mapping.targetField(), value);
		}
		return Map.copyOf(inputs);
	}

	private SourceField resolveSource(RoutePlanStep targetStep, String targetField, InputField target,
			Map<String, PublishedContract> contracts) {
		if (StringUtils.hasText(target.source())) {
			String[] parts = target.source().trim().split("\\.", -1);
			if (parts.length != 2 || !targetStep.dependsOn().contains(parts[0])) {
				throw invalid("Published input mapping references an invalid predecessor: " + targetField);
			}
			RouteDependencyValueType sourceType = contracts.get(parts[0]).outputs().get(parts[1]);
			if (sourceType != target.valueType()) {
				throw invalid("Published input mapping does not match a predecessor output: " + targetField);
			}
			return new SourceField(parts[0], parts[1]);
		}
		List<SourceField> candidates = targetStep.dependsOn().stream().filter(contracts::containsKey)
			.filter(stepId -> target.valueType() == contracts.get(stepId).outputs().get(targetField))
			.map(stepId -> new SourceField(stepId, targetField)).toList();
		if (candidates.size() != 1) {
			throw invalid("Published input mapping must identify exactly one predecessor output: " + targetField);
		}
		return candidates.get(0);
	}

	private PublishedContract resolveContract(DataAgent collaborator, String tenantId) {
		if (!StringUtils.hasText(tenantId) || !Objects.equals(tenantId, collaborator.getTenantId())) {
			throw invalid("Collaborator tenant binding is invalid");
		}
		List<DataAgentSkillBinding> bindings = bindingMapper.findEnabledByAgentId(collaborator.getId(), tenantId);
		if (bindings.isEmpty()) {
			throw invalid("Collaborator has no published capability contract");
		}
		Map<Long, DataAgentSkill> skills = skillMapper.selectBatchIds(bindings.stream()
			.map(DataAgentSkillBinding::getSkillId).filter(Objects::nonNull).distinct().toList()).stream()
			.collect(Collectors.toMap(DataAgentSkill::getId, item -> item));
		Map<Long, DataAgentSkillVersion> versions = versionMapper.selectBatchIds(bindings.stream()
			.map(DataAgentSkillBinding::getPinnedSkillVersionId).filter(Objects::nonNull).distinct().toList()).stream()
			.collect(Collectors.toMap(DataAgentSkillVersion::getId, item -> item));
		Map<String, RouteDependencyValueType> outputs = new LinkedHashMap<>();
		Map<String, InputField> inputs = new LinkedHashMap<>();
		for (DataAgentSkillBinding binding : bindings) {
			DataAgentSkill skill = skills.get(binding.getSkillId());
			DataAgentSkillVersion version = versions.get(binding.getPinnedSkillVersionId());
			if (!published(binding, skill, version, tenantId)) {
				throw invalid("Collaborator published capability contract is stale");
			}
			merge(outputs, fields(version.getOutputSchema()), "output");
			mergeInputs(inputs, inputFields(version.getInputSchema()));
		}
		return new PublishedContract(Map.copyOf(outputs), Map.copyOf(inputs));
	}

	private boolean published(DataAgentSkillBinding binding, DataAgentSkill skill, DataAgentSkillVersion version,
			String tenantId) {
		return binding != null && skill != null && version != null && !Boolean.TRUE.equals(binding.getDeleted())
				&& !Boolean.TRUE.equals(skill.getDeleted()) && !Boolean.TRUE.equals(version.getDeleted())
				&& Objects.equals(tenantId, binding.getTenantId()) && Objects.equals(tenantId, skill.getTenantId())
				&& Objects.equals(tenantId, version.getTenantId()) && Objects.equals(binding.getSkillId(), skill.getId())
				&& Objects.equals(binding.getPinnedSkillVersionId(), version.getId())
				&& Objects.equals(skill.getId(), version.getSkillId()) && "PUBLISHED".equals(skill.getStatus())
				&& "PUBLISHED".equals(version.getStatus());
	}

	private Map<String, RouteDependencyValueType> fields(String schema) {
		Map<String, Object> properties = properties(schema);
		Map<String, RouteDependencyValueType> fields = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : properties.entrySet()) {
			if (!(entry.getValue() instanceof Map<?, ?> definition)) {
				continue;
			}
			String transfer = text(definition.get(TRANSFER_EXTENSION));
			if (!StringUtils.hasText(transfer)) {
				continue;
			}
			fields.put(validField(entry.getKey()), valueType(transfer));
		}
		return Map.copyOf(fields);
	}

	private Map<String, InputField> inputFields(String schema) {
		Map<String, Object> properties = properties(schema);
		Map<String, InputField> fields = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : properties.entrySet()) {
			if (!(entry.getValue() instanceof Map<?, ?> definition)) {
				continue;
			}
			String transfer = text(definition.get(TRANSFER_EXTENSION));
			if (!StringUtils.hasText(transfer)) {
				continue;
			}
			String field = validField(entry.getKey());
			InputField previous = fields.put(field, new InputField(valueType(transfer), text(definition.get(SOURCE_EXTENSION))));
			if (previous != null) {
				throw invalid("Published input contract contains duplicate field: " + field);
			}
		}
		return Map.copyOf(fields);
	}

	private Map<String, Object> properties(String schema) {
		if (!StringUtils.hasText(schema)) {
			return Map.of();
		}
		try {
			Map<String, Object> root = objectMapper.readValue(schema, new TypeReference<>() {
			});
			Object properties = root.get("properties");
			if (!(properties instanceof Map<?, ?> map)) {
				return Map.of();
			}
			Map<String, Object> result = new LinkedHashMap<>();
			map.forEach((key, value) -> result.put(String.valueOf(key), value));
			return Map.copyOf(result);
		}
		catch (Exception ex) {
			throw invalid("Published Skill schema is invalid");
		}
	}

	private List<RouteStepOutputBinding> outputBindings(Map<String, RouteDependencyValueType> bindings) {
		if (bindings == null || bindings.isEmpty()) {
			return List.of();
		}
		return bindings.entrySet().stream().map(entry -> new RouteStepOutputBinding(entry.getKey(), entry.getValue()))
			.toList();
	}

	private void merge(Map<String, RouteDependencyValueType> target, Map<String, RouteDependencyValueType> values,
			String direction) {
		for (Map.Entry<String, RouteDependencyValueType> entry : values.entrySet()) {
			RouteDependencyValueType previous = target.putIfAbsent(entry.getKey(), entry.getValue());
			if (previous != null && previous != entry.getValue()) {
				throw invalid("Published " + direction + " contract has incompatible field type: " + entry.getKey());
			}
		}
	}

	private void mergeInputs(Map<String, InputField> target, Map<String, InputField> values) {
		for (Map.Entry<String, InputField> entry : values.entrySet()) {
			InputField previous = target.putIfAbsent(entry.getKey(), entry.getValue());
			if (previous != null && !previous.equals(entry.getValue())) {
				throw invalid("Published input contract has incompatible field mapping: " + entry.getKey());
			}
		}
	}

	private RouteDependencyValueType valueType(String value) {
		try {
			return RouteDependencyValueType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (RuntimeException ex) {
			throw invalid("Published Skill transfer type is invalid");
		}
	}

	private String validField(String value) {
		if (!StringUtils.hasText(value) || !value.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
			throw invalid("Published Skill transfer field is invalid");
		}
		return value.trim();
	}

	private boolean matches(RouteDependencyValueType type, JsonNode value) {
		return switch (type) {
			case ID -> value.isTextual() || value.isIntegralNumber()
					|| value.isArray() && value.size() <= 100 && java.util.stream.IntStream.range(0, value.size())
						.allMatch(index -> value.get(index).isTextual() || value.get(index).isIntegralNumber());
			case FILTER -> value.isObject();
			case STATISTIC -> value.isNumber()
					|| value.isObject() && java.util.stream.StreamSupport.stream(
							java.util.Spliterators.spliteratorUnknownSize(value.elements(), 0), false)
						.allMatch(JsonNode::isNumber);
		};
	}

	private boolean matches(RouteDependencyValueType type, Object value) {
		if (value == null) {
			return false;
		}
		JsonNode node = objectMapper.valueToTree(value);
		return matches(type, node);
	}

	private String text(Object value) {
		return value == null ? null : String.valueOf(value).trim();
	}

	private RouteStageException invalid(String message) {
		return new RouteStageException("ROUTE_PLAN_DEPENDENCY_CONTRACT_INVALID", message);
	}

	private record PublishedContract(Map<String, RouteDependencyValueType> outputs, Map<String, InputField> inputs) {
	}

	private record InputField(RouteDependencyValueType valueType, String source) {
	}

	private record SourceField(String stepId, String field) {
	}

}
