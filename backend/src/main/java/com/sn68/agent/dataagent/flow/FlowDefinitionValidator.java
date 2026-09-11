/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.flow.definition.FlowBranch;
import com.sn68.agent.dataagent.flow.definition.FlowDefinition;
import com.sn68.agent.dataagent.flow.definition.FlowNode;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Structural validator for the script-free FLOW DSL.
 */
@Component
public class FlowDefinitionValidator {

	private static final Set<String> NODE_TYPES = Set.of("extract", "collect", "review", "resolve", "select", "merge",
			"validate", "switch", "confirm", "execute", "present", "handoff", "end", "error");

	private static final Set<String> CONDITION_OPERATORS = Set.of("eq", "ne", "empty", "notEmpty", "gt", "gte",
			"lt", "lte", "in", "contains", "all", "any", "not");

	private static final Set<String> FORBIDDEN_KEYS = Set.of("script", "spel", "class", "className", "javaClass");

	private static final Set<String> UI_MODES = Set.of("CHAT", "FORM", "SUMMARY");

	private static final Set<String> UI_ACTION_TYPES = Set.of("SUBMIT", "CLEAR_REFERENCE", "CONFIRM", "EDIT",
			"CANCEL", "SELECT", "SKIP");

	private static final Set<String> EXTRACTION_MODEL_POLICIES = Set.of("ALWAYS", "IF_UNRESOLVED");

	private static final Set<String> EXTRACTION_SCHEMA_MODES = Set.of("FULL", "CONFIGURED_PATHS",
			"UNRESOLVED_REQUIRED");

	private static final Set<String> EXTRACTION_FAILURE_POLICIES = Set.of("WAIT_RETRY");

	private final ObjectMapper objectMapper;

	public FlowDefinitionValidator(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * 校验 FLOW 定义的结构合法性，返回全部错误描述（空列表表示通过）。
	 * 校验项覆盖：禁用脚本键、schema 版本、节点类型与唯一性、各类节点配置、跳转目标、
	 * execute 必须有 confirm 前置、Resolver 依赖有序等。
	 */
	public List<String> validate(Map<String, Object> rawDefinition) {
		if (rawDefinition == null || rawDefinition.isEmpty()) {
			return List.of("FLOW definition is required");
		}
		List<String> errors = new ArrayList<>();
		if (containsForbiddenKey(rawDefinition)) {
			errors.add("FLOW definition must not contain scripts, SpEL or Java class names");
		}
		FlowDefinition definition;
		try {
			definition = objectMapper.convertValue(rawDefinition, FlowDefinition.class);
		}
		catch (IllegalArgumentException ex) {
			return List.of("FLOW definition cannot be parsed: " + ex.getMessage());
		}
		if (!Set.of("skill-flow/v1", "skill-flow/v2").contains(definition.schemaVersion())) {
			errors.add("schemaVersion must be skill-flow/v1 or skill-flow/v2");
		}
		if (!StringUtils.hasText(definition.startNode())) {
			errors.add("startNode is required");
		}
		validateTemporalSchema("variablesSchema", definition.variablesSchema(), errors);
		List<FlowNode> nodes = definition.nodes() == null ? List.of() : definition.nodes();
		if (nodes.isEmpty()) {
			errors.add("FLOW must contain at least one node");
			return List.copyOf(errors);
		}
		Set<String> nodeIds = new HashSet<>();
		boolean hasConfirm = false;
		boolean hasEnd = false;
		for (FlowNode node : nodes) {
			if (node == null || !StringUtils.hasText(node.id())) {
				errors.add("Every FLOW node must have an id");
				continue;
			}
			if (!nodeIds.add(node.id())) {
				errors.add("Duplicate FLOW node id: " + node.id());
			}
			String type = normalize(node.type());
			if (!NODE_TYPES.contains(type)) {
				errors.add("Unsupported FLOW node type: " + node.type());
			}
			hasConfirm |= "confirm".equals(type);
			hasEnd |= "end".equals(type);
			if ("execute".equals(type)) {
				validateExecuteNodeConfig(node, errors);
			}
			if ("resolve".equals(type)) {
				validateResolverConfig(node.id(), node.config(), errors);
			}
			validateUiConfig(node.id(), node.config(), errors);
			validateUiActions(node.id(), type, node.config(), "skill-flow/v2".equals(definition.schemaVersion()), errors);
			validateLiteralMappings(node.id(), node.config(), errors);
			validateExtraction(node.id(), type, node.config(), errors);
			validateCollectionPresentation(node.id(), type, node.config(), errors);
			validateTemporalSchema(node.id(), map(node.config() == null ? null : node.config().get("schema")), errors);
			if (node.branches() != null) {
				for (FlowBranch branch : node.branches()) {
					validateCondition(branch == null ? null : branch.condition(), node.id(), errors);
				}
			}
		}
		if (!nodeIds.contains(definition.startNode())) {
			errors.add("startNode does not reference an existing node");
		}
		Map<String, FlowNode> nodeMap = new HashMap<>();
		nodes.stream().filter(node -> node != null && StringUtils.hasText(node.id()))
				.forEach(node -> nodeMap.putIfAbsent(node.id(), node));
		validateNodeTargets(nodes, nodeIds, nodeMap, errors);
		if (!hasEnd) {
			errors.add("FLOW must contain an end node");
		}
		if (nodes.stream().anyMatch(node -> node != null && "execute".equals(normalize(node.type()))) && !hasConfirm) {
			errors.add("FLOW containing execute must contain a confirm node");
		}
		validateExecuteConfirmation(definition, nodeMap, errors);
		validateResolverDependencies(nodes, errors);
		return List.copyOf(errors);
	}

	/** execute 节点配置校验：必须钉住工具版本，successCondition 与 resultQuery 条件结构合法。 */
	private void validateExecuteNodeConfig(FlowNode node, List<String> errors) {
		if (!hasText(node.config(), "resourceVersionId")) {
			errors.add("execute node " + node.id() + " must pin resourceVersionId");
		}
		Map<String, Object> resultQuery = map(node.config() == null ? null : node.config().get("resultQuery"));
		Map<String, Object> successCondition = map(node.config() == null ? null : node.config().get("successCondition"));
		if (node.config() != null && node.config().containsKey("successCondition")) {
			validateCondition(successCondition, node.id(), errors);
		}
		if (!resultQuery.isEmpty()) {
			if (!hasText(resultQuery, "resourceVersionId")) {
				errors.add("execute node " + node.id() + " must pin a resultQuery resourceVersionId");
			}
			Map<String, Object> completedCondition = map(resultQuery.get("completedCondition"));
			if (completedCondition.isEmpty()) {
				errors.add("execute node " + node.id() + " must define resultQuery completedCondition");
			}
			else {
				validateCondition(completedCondition, node.id(), errors);
			}
		}
	}

	/** 校验所有节点的跳转目标（next/emptyNext/invalidNext/refreshNext/editNode/分支等）都指向存在的节点。 */
	private void validateNodeTargets(List<FlowNode> nodes, Set<String> nodeIds, Map<String, FlowNode> nodeMap,
			List<String> errors) {
		for (FlowNode node : nodes) {
			if (node == null) {
				continue;
			}
			validateTarget(node.next(), node.id(), nodeIds, errors);
			Map<String, Object> config = node.config() == null ? Map.of() : node.config();
			validateTarget(text(config.get("emptyNext")), node.id(), nodeIds, errors);
			validateTarget(text(config.get("invalidNext")), node.id(), nodeIds, errors);
			validateTarget(text(config.get("refreshNext")), node.id(), nodeIds, errors);
			validateTarget(text(config.get("editNode")), node.id(), nodeIds, errors);
			validateReviewRoutesAndIssues(node.id(), normalize(node.type()), config, nodeIds, errors);
			validateTextSearch(node, config, nodeMap, errors);
			validateCatalogQueries(node, config, nodeMap, errors);
			validateFallback(node.id(), normalize(node.type()), config, nodeMap, errors);
			Map<String, Object> resultQuery = map(config.get("resultQuery"));
			validateTarget(text(resultQuery.get("next")), node.id(), nodeIds, errors);
			if (node.branches() != null) {
				node.branches().forEach(branch -> validateTarget(branch == null ? null : branch.next(), node.id(),
						nodeIds, errors));
			}
		}
	}

	private void validateExecuteConfirmation(FlowDefinition definition, Map<String, FlowNode> nodeMap,
			List<String> errors) {
		if (!nodeMap.containsKey(definition.startNode()) || nodeMap.values().stream()
			.noneMatch(node -> "execute".equals(normalize(node.type())))) {
			return;
		}
		Deque<FlowVisit> pending = new ArrayDeque<>();
		pending.add(new FlowVisit(definition.startNode(), false));
		Set<FlowVisit> visited = new HashSet<>();
		Set<String> reported = new HashSet<>();
		while (!pending.isEmpty()) {
			FlowVisit visit = pending.removeFirst();
			if (!visited.add(visit)) {
				continue;
			}
			FlowNode node = nodeMap.get(visit.nodeId());
			if (node == null) {
				continue;
			}
			String type = normalize(node.type());
			if ("execute".equals(type) && !visit.confirmed() && reported.add(node.id())) {
				errors.add("execute node " + node.id() + " is reachable before a confirm node");
			}
			boolean confirmed = visit.confirmed() || "confirm".equals(type);
			for (String target : staticTargets(node)) {
				if (nodeMap.containsKey(target)) {
					pending.addLast(new FlowVisit(target, confirmed));
				}
			}
			String editNode = text((node.config() == null ? Map.of() : node.config()).get("editNode"));
			if (StringUtils.hasText(editNode) && nodeMap.containsKey(editNode)) {
				pending.addLast(new FlowVisit(editNode, false));
			}
		}
	}

	private List<String> staticTargets(FlowNode node) {
		List<String> targets = new ArrayList<>();
		if (StringUtils.hasText(node.next())) {
			targets.add(node.next());
		}
		Map<String, Object> config = node.config() == null ? Map.of() : node.config();
		for (String key : List.of("emptyNext", "invalidNext", "refreshNext", "editNode")) {
			String target = text(config.get(key));
			if (StringUtils.hasText(target)) {
				targets.add(target);
			}
		}
		if (node.branches() != null) {
			node.branches().stream().filter(branch -> branch != null && StringUtils.hasText(branch.next()))
				.map(FlowBranch::next).forEach(targets::add);
		}
		for (Object routeValue : iterable(config.get("refreshRoutes"))) {
			String target = text(map(routeValue).get("next"));
			if (StringUtils.hasText(target)) {
				targets.add(target);
			}
		}
		String resultNext = text(map(config.get("resultQuery")).get("next"));
		if (StringUtils.hasText(resultNext)) {
			targets.add(resultNext);
		}
		return targets;
	}

	private record FlowVisit(String nodeId, boolean confirmed) {
	}

	public List<FlowValidationIssue> validateIssues(Map<String, Object> rawDefinition) {
		return validate(rawDefinition).stream().map(FlowValidationIssue::error).toList();
	}

	private void validateCollectionPresentation(String nodeId, String nodeType, Map<String, Object> config,
			List<String> errors) {
		if (config == null || !config.containsKey("collectionPresentation")) {
			return;
		}
		if (!"collect".equals(nodeType)) {
			errors.add("collectionPresentation is only allowed on collect node " + nodeId);
			return;
		}
		if (!(config.get("collectionPresentation") instanceof Map<?, ?> rawPresentation)) {
			errors.add("collectionPresentation must be an object on node " + nodeId);
			return;
		}
		Map<String, Object> presentation = castMap(rawPresentation);
		if (!"MISSING_ONLY".equals(text(presentation.get("mode")))) {
			errors.add("collectionPresentation.mode must be MISSING_ONLY on node " + nodeId);
		}
		if (!(presentation.get("fieldPrompts") instanceof Map<?, ?> rawPrompts)) {
			errors.add("collectionPresentation.fieldPrompts must be an object on node " + nodeId);
			return;
		}
		Set<String> requiredPaths = new HashSet<>(strings(config.get("requiredPaths")));
		requiredPaths.addAll(strings(config.get("requiredAnyPaths")));
		Map<String, Object> prompts = castMap(rawPrompts);
		for (Map.Entry<String, Object> entry : prompts.entrySet()) {
			if (!requiredPaths.contains(entry.getKey())) {
				errors.add("collectionPresentation path " + entry.getKey()
						+ " is not a required path on node " + nodeId);
			}
			if (!StringUtils.hasText(text(entry.getValue()))) {
				errors.add("collectionPresentation prompt must not be blank for " + entry.getKey()
						+ " on node " + nodeId);
			}
			else if (containsTechnicalPrompt(text(entry.getValue()))) {
				errors.add("collectionPresentation prompt must use a business term instead of an internal field for "
						+ entry.getKey() + " on node " + nodeId);
			}
		}
		for (String requiredPath : requiredPaths) {
			if (!prompts.containsKey(requiredPath) || !StringUtils.hasText(text(prompts.get(requiredPath)))) {
				errors.add("collectionPresentation must define prompt for " + requiredPath + " on node " + nodeId);
			}
		}
	}

	private void validateTextSearch(FlowNode node, Map<String, Object> config, Map<String, FlowNode> nodeMap,
			List<String> errors) {
		if (config == null || !config.containsKey("textSearch")) {
			return;
		}
		if (!(config.get("textSearch") instanceof Map<?, ?> raw)) {
			errors.add("textSearch must be an object on node " + node.id());
			return;
		}
		Map<String, Object> textSearch = castMap(raw);
		if (!Boolean.TRUE.equals(textSearch.get("enabled"))) {
			errors.add("textSearch.enabled must be true on node " + node.id());
		}
		String nodeType = normalize(node.type());
		boolean collectionResolver = "resolve".equals(nodeType) && StringUtils.hasText(text(config.get("forEach")));
		if (!"select".equals(nodeType) && !collectionResolver) {
			errors.add("textSearch is only allowed on select or collection resolve node " + node.id());
		}
		String resolverNodeId = text(textSearch.get("resolverNode"));
		FlowNode resolverNode = nodeMap.get(resolverNodeId);
		if (!StringUtils.hasText(resolverNodeId) || resolverNode == null
				|| !"resolve".equals(normalize(resolverNode.type()))) {
			errors.add("textSearch.resolverNode must reference a resolve node on " + node.id());
		}
		if (collectionResolver && !node.id().equals(resolverNodeId)) {
			errors.add("collection textSearch must reference its own resolver node on " + node.id());
		}
		if (!StringUtils.hasText(text(textSearch.get("argumentName")))) {
			errors.add("textSearch.argumentName is required on node " + node.id());
		}
		Object rawPaths = textSearch.get("matchPaths");
		if (!(rawPaths instanceof Iterable<?> paths) || !paths.iterator().hasNext()) {
			errors.add("textSearch.matchPaths must be a non-empty array on node " + node.id());
			return;
		}
		for (Object path : paths) {
			String value = text(path);
			if (!StringUtils.hasText(value) || !isJsonPointer(value)) {
				errors.add("textSearch.matchPaths must contain valid JSON Pointer values on node " + node.id());
			}
		}
		if (!StringUtils.hasText(text(textSearch.get("notFoundText")))) {
			errors.add("textSearch.notFoundText is required on node " + node.id());
		}
	}

	private void validateCatalogQueries(FlowNode node, Map<String, Object> config, Map<String, FlowNode> nodeMap,
			List<String> errors) {
		if (config == null || !config.containsKey("catalogQueries")) {
			return;
		}
		if (!"collect".equals(normalize(node.type()))) {
			errors.add("catalogQueries is only allowed on collect node " + node.id());
			return;
		}
		if (!(config.get("catalogQueries") instanceof Iterable<?> queries) || !queries.iterator().hasNext()) {
			errors.add("catalogQueries must be a non-empty array on node " + node.id());
			return;
		}
		Set<String> requiredPaths = new HashSet<>(strings(config.get("requiredPaths")));
		for (Object item : queries) {
			if (!(item instanceof Map<?, ?> raw)) {
				errors.add("catalogQueries entries must be objects on node " + node.id());
				continue;
			}
			Map<String, Object> query = castMap(raw);
			String whenMissing = text(query.get("whenMissing"));
			if (!StringUtils.hasText(whenMissing) || !isJsonPointer(whenMissing)
					|| !requiredPaths.contains(whenMissing)) {
				errors.add("catalogQueries.whenMissing must be a required path on node " + node.id());
			}
			if (!(query.get("intents") instanceof Iterable<?> intents) || !intents.iterator().hasNext()) {
				errors.add("catalogQueries.intents must be a non-empty array on node " + node.id());
			}
			String resolverNodeId = text(query.get("resolverNode"));
			FlowNode resolverNode = nodeMap.get(resolverNodeId);
			if (!StringUtils.hasText(resolverNodeId) || resolverNode == null
					|| !"resolve".equals(normalize(resolverNode.type()))) {
				errors.add("catalogQueries.resolverNode must reference a resolve node on " + node.id());
			}
		}
	}

	private boolean isJsonPointer(String value) {
		if (!value.startsWith("/")) {
			return false;
		}
		for (int index = 0; index < value.length(); index++) {
			if (value.charAt(index) != '~') {
				continue;
			}
			if (index + 1 >= value.length() || (value.charAt(index + 1) != '0' && value.charAt(index + 1) != '1')) {
				return false;
			}
			index++;
		}
		return true;
	}

	/**
	 * select 节点死路回退配置校验：fallbackNode 必须指向本流程存在的节点、不得自指、
	 * 不得指向 execute 节点（回退禁止自动触发业务写入），且必须声明非空的 fallbackClears 路径。
	 */
	private void validateFallback(String nodeId, String nodeType, Map<String, Object> config,
			Map<String, FlowNode> nodeMap, List<String> errors) {
		if (config == null || !"select".equals(nodeType) || !config.containsKey("fallbackNode")) {
			return;
		}
		String fallbackNode = text(config.get("fallbackNode"));
		if (!StringUtils.hasText(fallbackNode)) {
			errors.add("select node " + nodeId + " fallbackNode must not be blank when configured");
			return;
		}
		if (fallbackNode.equals(nodeId)) {
			errors.add("select node " + nodeId + " fallbackNode must not reference itself");
		}
		FlowNode target = nodeMap.get(fallbackNode);
		if (target == null) {
			errors.add("Node " + nodeId + " references missing node " + fallbackNode);
		}
		else if ("execute".equals(normalize(target.type()))) {
			errors.add("select node " + nodeId + " fallbackNode must not reference an execute node");
		}
		if (!(config.get("fallbackClears") instanceof Iterable<?> paths) || !paths.iterator().hasNext()) {
			errors.add("select node " + nodeId
					+ " fallbackClears must be a non-empty array when fallbackNode is configured");
			return;
		}
		for (Object path : paths) {
			if (!(path instanceof String pointer) || !isJsonPointer(pointer)) {
				errors.add("select node " + nodeId + " fallbackClears must contain JSON Pointer values");
			}
		}
	}

	private void validateTemporalSchema(String nodeId, Map<String, Object> schema, List<String> errors) {
		if (schema == null || schema.isEmpty()) {
			return;
		}
		for (Map.Entry<String, Object> entry : map(schema.get("properties")).entrySet()) {
			Map<String, Object> property = map(entry.getValue());
			Object temporalValue = property.get("x-temporal");
			if (temporalValue != null) {
				Map<String, Object> temporal = temporalValue instanceof String value ? Map.of("kind", value)
						: map(temporalValue);
				String kind = text(temporal.get("kind"));
				if (!Set.of("DATE", "DATE_TIME", "DATE_OR_DATE_TIME", "INTERVAL").contains(
						StringUtils.hasText(kind) ? kind.trim().toUpperCase(Locale.ROOT) : "DATE_OR_DATE_TIME")) {
					errors.add("Unsupported x-temporal kind on " + nodeId + "." + entry.getKey());
				}
				String targetType = text(temporal.get("targetType"));
				if ("INTERVAL".equalsIgnoreCase(kind)) {
					if (!"object".equalsIgnoreCase(text(property.get("type")))) {
						errors.add("x-temporal INTERVAL requires object schema on " + nodeId + "." + entry.getKey());
					}
					if (StringUtils.hasText(targetType) || StringUtils.hasText(text(temporal.get("dateLanding")))) {
						errors.add("x-temporal INTERVAL does not support targetType or dateLanding on " + nodeId + "."
								+ entry.getKey());
					}
				}
				else if (StringUtils.hasText(targetType) && !Set.of("DATE", "DATE_TIME", "INSTANT")
						.contains(targetType.trim().toUpperCase(Locale.ROOT))) {
					errors.add("Unsupported x-temporal targetType on " + nodeId + "." + entry.getKey());
				}
				String dateLanding = text(temporal.get("dateLanding"));
				if (StringUtils.hasText(dateLanding)
						&& !"START_OF_DAY".equals(dateLanding.trim().toUpperCase(Locale.ROOT))) {
					errors.add("Unsupported x-temporal dateLanding on " + nodeId + "." + entry.getKey());
				}
				if (StringUtils.hasText(dateLanding)
						&& !"INSTANT".equalsIgnoreCase(StringUtils.hasText(targetType) ? targetType.trim() : "INSTANT")) {
					errors.add("x-temporal dateLanding requires targetType=INSTANT on " + nodeId + "."
							+ entry.getKey());
				}
			}
			validateTemporalSchema(nodeId, property, errors);
			validateTemporalSchema(nodeId, map(property.get("items")), errors);
		}
	}

	private void validateUiConfig(String nodeId, Map<String, Object> config, List<String> errors) {
		if (config == null || !config.containsKey("uiMode")) {
			return;
		}
		String mode = String.valueOf(config.get("uiMode")).trim().toUpperCase(Locale.ROOT);
		if (!UI_MODES.contains(mode)) {
			errors.add("Unsupported uiMode " + config.get("uiMode") + " on node " + nodeId);
			return;
		}
		if ("FORM".equals(mode)) {
			Map<String, Object> uiSchema = map(config.get("uiSchema"));
			if (map(uiSchema.get("properties")).isEmpty()) {
				errors.add("FORM node " + nodeId + " must define uiSchema properties");
			}
			else if (containsBusinessIdProperty(uiSchema)) {
				errors.add("FORM node " + nodeId + " uiSchema must not expose business ID fields");
			}
		}
		if ("SUMMARY".equals(mode) && !hasItems(config.get("displayFields"))
				&& !hasItems(config.get("displayCollections"))) {
			errors.add("SUMMARY node " + nodeId + " must define displayFields or displayCollections");
		}
		if ("SUMMARY".equals(mode) && containsBusinessIdDisplayPath(config)) {
			errors.add("SUMMARY node " + nodeId + " must not expose business ID paths");
		}
	}

	private void validateUiActions(String nodeId, String nodeType, Map<String, Object> config, boolean required,
			List<String> errors) {
		if (config == null || !config.containsKey("uiActions")) {
			boolean collectionResolver = "resolve".equals(nodeType)
					&& StringUtils.hasText(text(config == null ? null : config.get("forEach")));
			if (required && (Set.of("collect", "validate", "review", "confirm", "select").contains(nodeType)
					|| collectionResolver)) {
				errors.add("skill-flow/v2 waiting node " + nodeId + " must define uiActions");
			}
			return;
		}
		Object rawActions = config.get("uiActions");
		if (!(rawActions instanceof Iterable<?> iterable)) {
			errors.add("uiActions must be an array on node " + nodeId);
			return;
		}
		Set<String> actionIds = new HashSet<>();
		for (Object item : iterable) {
			if (!(item instanceof Map<?, ?> raw)) {
				errors.add("uiActions entries must be objects on node " + nodeId);
				continue;
			}
			Map<String, Object> action = castMap(raw);
			String actionId = text(action.get("actionId"));
			String actionType = text(action.get("type")).toUpperCase(Locale.ROOT);
			if (!StringUtils.hasText(actionId) || !actionIds.add(actionId)) {
				errors.add("uiActions actionId must be unique and non-blank on node " + nodeId);
			}
			if (!UI_ACTION_TYPES.contains(actionType)) {
				errors.add("Unsupported uiActions type " + action.get("type") + " on node " + nodeId);
			}
			if (!StringUtils.hasText(text(action.get("label")))) {
				errors.add("uiActions label must not be blank on node " + nodeId);
			}
			if (!actionAllowedForNode(nodeType, actionType)) {
				errors.add("uiActions type " + actionType + " is not allowed on node " + nodeId);
			}
		}
		if (containsAction(rawActions, "CANCEL")) {
			Map<String, Object> cancelPolicy = map(config.get("cancelPolicy"));
			if (cancelPolicy.isEmpty()) {
				errors.add("CANCEL action on node " + nodeId + " must define cancelPolicy");
			}
			else {
				for (String key : List.of("question", "confirmLabel", "keepLabel", "continueText", "successText")) {
					if (!StringUtils.hasText(text(cancelPolicy.get(key)))) {
						errors.add("cancelPolicy." + key + " must not be blank on node " + nodeId);
					}
				}
			}
		}
	}

	private boolean actionAllowedForNode(String nodeType, String actionType) {
		return switch (nodeType) {
			case "collect", "validate" -> Set.of("SUBMIT", "CANCEL").contains(actionType);
			case "review" -> Set.of("SUBMIT", "CLEAR_REFERENCE", "CANCEL").contains(actionType);
			case "confirm" -> Set.of("CONFIRM", "EDIT", "CANCEL").contains(actionType);
			case "select", "resolve" -> Set.of("SELECT", "SKIP", "CANCEL").contains(actionType);
			default -> false;
		};
	}

	private boolean containsAction(Object rawActions, String type) {
		if (!(rawActions instanceof Iterable<?> iterable)) {
			return false;
		}
		for (Object item : iterable) {
			if (item instanceof Map<?, ?> map && type.equalsIgnoreCase(text(map.get("type")))) {
				return true;
			}
		}
		return false;
	}

	private void validateLiteralMappings(String nodeId, Map<String, Object> config, List<String> errors) {
		if (config == null || !config.containsKey("literalMappings")) {
			return;
		}
		Object rawMappings = config.get("literalMappings");
		if (!(rawMappings instanceof Map<?, ?> rawMappingMap)) {
			errors.add("literalMappings must be an object on node " + nodeId);
			return;
		}
		Map<String, Object> properties = map(map(config.get("schema")).get("properties"));
		Map<String, Object> mappings = castMap(rawMappingMap);
		for (Map.Entry<String, Object> fieldEntry : mappings.entrySet()) {
			String field = fieldEntry.getKey();
			if (!properties.containsKey(field)) {
				errors.add("literalMappings field " + field + " is not defined in schema on node " + nodeId);
			}
			if (!(fieldEntry.getValue() instanceof Map<?, ?> rawValues)) {
				errors.add("literalMappings values must be an object for field " + field + " on node " + nodeId);
				continue;
			}
			Map<String, String> aliasOwners = new HashMap<>();
			for (Map.Entry<String, Object> valueEntry : castMap(rawValues).entrySet()) {
				String canonicalValue = valueEntry.getKey();
				if (!StringUtils.hasText(canonicalValue)) {
					errors.add("literalMappings canonical value must not be blank for field " + field
							+ " on node " + nodeId);
				}
				if (!(valueEntry.getValue() instanceof Iterable<?> aliases) || !aliases.iterator().hasNext()) {
					errors.add("literalMappings aliases must be a non-empty array for " + field + "."
							+ canonicalValue + " on node " + nodeId);
					continue;
				}
				for (Object aliasValue : aliases) {
					if (!(aliasValue instanceof String alias) || !StringUtils.hasText(alias)) {
						errors.add("literalMappings aliases must contain non-blank strings for " + field + "."
								+ canonicalValue + " on node " + nodeId);
						continue;
					}
					String normalizedAlias = normalize(alias);
					String owner = aliasOwners.putIfAbsent(normalizedAlias, canonicalValue);
					if (owner != null && !owner.equals(canonicalValue)) {
						errors.add("literalMappings alias conflicts on field " + field + ": " + alias);
					}
				}
			}
		}
	}

	private void validateExtraction(String nodeId, String nodeType, Map<String, Object> config, List<String> errors) {
		if (config == null || !config.containsKey("extraction")) {
			return;
		}
		if (!(config.get("extraction") instanceof Map<?, ?> rawExtraction)) {
			errors.add("extraction must be an object on node " + nodeId);
			return;
		}
		Map<String, Object> extraction = castMap(rawExtraction);
		String modelPolicy = normalizedConfigValue(extraction.get("modelPolicy"));
		if (StringUtils.hasText(modelPolicy) && !EXTRACTION_MODEL_POLICIES.contains(modelPolicy)) {
			errors.add("Unsupported extraction modelPolicy " + extraction.get("modelPolicy") + " on node " + nodeId);
		}
		String schemaMode = normalizedConfigValue(extraction.get("schemaMode"));
		if (StringUtils.hasText(schemaMode) && !EXTRACTION_SCHEMA_MODES.contains(schemaMode)) {
			errors.add("Unsupported extraction schemaMode " + extraction.get("schemaMode") + " on node " + nodeId);
		}
		String failurePolicy = normalizedConfigValue(extraction.get("failurePolicy"));
		if (StringUtils.hasText(failurePolicy) && !EXTRACTION_FAILURE_POLICIES.contains(failurePolicy)) {
			errors.add("Unsupported extraction failurePolicy " + extraction.get("failurePolicy") + " on node " + nodeId);
		}
		if ("IF_UNRESOLVED".equals(modelPolicy) && !hasItems(config.get("requiredPaths"))
				&& !hasItems(config.get("requiredAnyPaths"))) {
			errors.add("extraction modelPolicy IF_UNRESOLVED requires requiredPaths or requiredAnyPaths on node "
					+ nodeId);
		}
		if ("CONFIGURED_PATHS".equals(schemaMode) && !hasItems(extraction.get("paths"))) {
			errors.add("extraction schemaMode CONFIGURED_PATHS requires non-empty paths on node " + nodeId);
			return;
		}
		if (extraction.containsKey("paths") && !(extraction.get("paths") instanceof Iterable<?>)) {
			errors.add("extraction paths must be an array on node " + nodeId);
			return;
		}
		Map<String, Object> schema = map(config.get("schema"));
		String outputPath = StringUtils.hasText(text(config.get("outputPath"))) ? text(config.get("outputPath")) : "/input";
		for (Object item : iterable(extraction.get("paths"))) {
			if (!(item instanceof String path) || !path.startsWith("/")) {
				errors.add("extraction paths must contain JSON Pointers on node " + nodeId);
				continue;
			}
			if (!schemaContainsPath(schema, outputPath, path)) {
				errors.add("extraction path " + path + " is not defined in schema on node " + nodeId);
			}
		}
	}

	private boolean containsTechnicalPrompt(String prompt) {
		String normalized = prompt == null ? "" : prompt.trim();
		if (normalized.startsWith("/") || normalized.contains("/input/") || normalized.contains("/runtime/")) {
			return true;
		}
		if (normalized.matches(".*\\b[a-z]+(?:[A-Z][A-Za-z0-9]*)+\\b.*")) {
			return true;
		}
		return normalized.matches("(?is).*?(?:^|[^A-Za-z])(?:id|ids)(?:$|[^A-Za-z]).*");
	}

	private boolean schemaContainsPath(Map<String, Object> schema, String outputPath, String path) {
		String prefix = outputPath.endsWith("/") ? outputPath : outputPath + "/";
		if (!path.startsWith(prefix) || path.length() == prefix.length()) {
			return false;
		}
		Map<String, Object> currentSchema = schema;
		String[] segments = path.substring(prefix.length()).split("/");
		for (String segment : segments) {
			Map<String, Object> property = map(map(currentSchema.get("properties")).get(unescapePointer(segment)));
			if (property.isEmpty()) {
				return false;
			}
			currentSchema = "array".equals(text(property.get("type"))) ? map(property.get("items")) : property;
		}
		return true;
	}

	private String normalizedConfigValue(Object value) {
		return value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
	}

	private String unescapePointer(String value) {
		return value.replace("~1", "/").replace("~0", "~");
	}

	private boolean containsBusinessIdDisplayPath(Map<String, Object> config) {
		if (containsBusinessIdPath(config.get("displayFields"))) {
			return true;
		}
		for (Object item : iterable(config.get("displayCollections"))) {
			Map<String, Object> collection = map(item);
			if (isBusinessIdPath(text(collection.get("path")))
					|| containsBusinessIdPath(collection.get("itemFields"))) {
				return true;
			}
		}
		return false;
	}

	private boolean containsBusinessIdPath(Object value) {
		for (Object item : iterable(value)) {
			if (isBusinessIdPath(text(map(item).get("path")))) {
				return true;
			}
		}
		return false;
	}

	private boolean isBusinessIdPath(String path) {
		if (!StringUtils.hasText(path)) {
			return false;
		}
		String[] segments = path.split("/");
		return segments.length > 0 && isBusinessIdName(segments[segments.length - 1]);
	}

	private boolean isBusinessIdName(String name) {
		if (!StringUtils.hasText(name)) {
			return false;
		}
		String lower = name.trim().toLowerCase(Locale.ROOT);
		if ("id".equals(lower) || "ids".equals(lower) || lower.endsWith("_id") || lower.endsWith("_ids")
				|| lower.endsWith("-id") || lower.endsWith("-ids") || name.endsWith("Id") || name.endsWith("Ids")) {
			return true;
		}
		return lower.endsWith("id") && List.of("company", "customer", "project", "product", "site", "industry",
				"contract", "demand", "order", "bill").stream().anyMatch(lower::contains);
	}

	private boolean containsBusinessIdProperty(Map<String, Object> schema) {
		for (Map.Entry<String, Object> entry : map(schema.get("properties")).entrySet()) {
			String name = entry.getKey();
			if (isBusinessIdName(name)) {
				return true;
			}
			Map<String, Object> property = map(entry.getValue());
			if (containsBusinessIdProperty(property) || containsBusinessIdProperty(map(property.get("items")))) {
				return true;
			}
		}
		return false;
	}

	private Iterable<?> iterable(Object value) {
		return value instanceof Iterable<?> iterable ? iterable : List.of();
	}

	private boolean hasItems(Object value) {
		return value instanceof Iterable<?> iterable && iterable.iterator().hasNext();
	}

	private void validateResolverConfig(String nodeId, Map<String, Object> config, List<String> errors) {
		if (config == null) {
			return;
		}
		validatePathList(config.get("reads"), nodeId, "reads", errors);
		validatePathList(config.get("requiresAll"), nodeId, "requiresAll", errors);
		validatePathList(config.get("requiresAny"), nodeId, "requiresAny", errors);
		validatePathList(config.get("writes"), nodeId, "writes", errors);
		validatePathList(config.get("invalidates"), nodeId, "invalidates", errors);
		if (config.containsKey("issuesPath") && !text(config.get("issuesPath")).startsWith("/")) {
			errors.add("Resolver issuesPath must use JSON Pointer on node " + nodeId);
		}
		Object parallelResolvers = config.get("parallelResolvers");
		if (!(parallelResolvers instanceof Iterable<?> iterable)) {
			return;
		}
		Set<String> outputPaths = new HashSet<>();
		for (Object item : iterable) {
			if (!(item instanceof Map<?, ?> raw)) {
				errors.add("Parallel resolver entries must be objects on node " + nodeId);
				continue;
			}
			Map<String, Object> resolver = castMap(raw);
			String resolverId = String.valueOf(resolver.getOrDefault("id", "resolver"));
			if (!hasText(resolver, "resourceVersionId")) {
				errors.add("Parallel resolver " + resolverId + " must pin resourceVersionId");
			}
			if (!Boolean.TRUE.equals(resolver.get("parallelSafe"))) {
				errors.add("Parallel resolver " + resolverId + " must set parallelSafe=true");
			}
			if ("WRITE".equalsIgnoreCase(String.valueOf(resolver.get("accessMode")))) {
				errors.add("Parallel resolver " + resolverId + " cannot use WRITE accessMode");
			}
			String outputPath = String.valueOf(resolver.getOrDefault("outputPath", "/resolved/" + resolverId));
			if (!outputPath.startsWith("/")) {
				errors.add("Parallel resolver outputPath must use JSON Pointer: " + resolverId);
			}
			else if (!outputPaths.add(outputPath)) {
				errors.add("Parallel resolver output paths conflict: " + outputPath);
			}
			validatePathList(resolver.get("requiresAll"), resolverId, "requiresAll", errors);
			validatePathList(resolver.get("requiresAny"), resolverId, "requiresAny", errors);
			validatePathList(resolver.get("reads"), resolverId, "reads", errors);
			validatePathList(resolver.get("writes"), resolverId, "writes", errors);
			validatePathList(resolver.get("invalidates"), resolverId, "invalidates", errors);
		}
	}

	private void validatePathList(Object value, String nodeId, String field, List<String> errors) {
		if (!(value instanceof Iterable<?> iterable)) {
			return;
		}
		for (Object item : iterable) {
			if (!(item instanceof String path) || !path.startsWith("/")) {
				errors.add("Resolver " + field + " paths must use JSON Pointer on node " + nodeId);
			}
		}
	}

	private void validateReviewRoutesAndIssues(String nodeId, String nodeType, Map<String, Object> config,
			Set<String> nodeIds, List<String> errors) {
		if (!"review".equals(nodeType)) {
			return;
		}
		Object rawRoutes = config.get("refreshRoutes");
		if (rawRoutes != null && !(rawRoutes instanceof Iterable<?>)) {
			errors.add("review refreshRoutes must be an array on node " + nodeId);
		}
		for (Object item : iterable(rawRoutes)) {
			if (!(item instanceof Map<?, ?> rawRoute)) {
				errors.add("review refreshRoutes entries must be objects on node " + nodeId);
				continue;
			}
			Map<String, Object> route = castMap(rawRoute);
			if (!hasItems(route.get("whenAny"))) {
				errors.add("review refreshRoutes.whenAny must be a non-empty array on node " + nodeId);
			}
			validateJsonPointerList(route.get("whenAny"), nodeId, "refreshRoutes.whenAny", errors);
			validateTarget(text(route.get("next")), nodeId, nodeIds, errors);
		}
		if (config.containsKey("issuesPath")) {
			String issuesPath = text(config.get("issuesPath"));
			if (!StringUtils.hasText(issuesPath) || !issuesPath.startsWith("/")) {
				errors.add("review issuesPath must use JSON Pointer on node " + nodeId);
			}
		}
		if (config.containsKey("issuesPaths")) {
			if (!hasItems(config.get("issuesPaths"))) {
				errors.add("review issuesPaths must be a non-empty array on node " + nodeId);
			}
			validateJsonPointerList(config.get("issuesPaths"), nodeId, "issuesPaths", errors);
		}
	}

	private void validateJsonPointerList(Object value, String nodeId, String field, List<String> errors) {
		if (!(value instanceof Iterable<?> iterable)) {
			return;
		}
		for (Object item : iterable) {
			if (!(item instanceof String path) || !path.startsWith("/")) {
				errors.add("review " + field + " must contain JSON Pointers on node " + nodeId);
			}
		}
	}

	private void validateResolverDependencies(List<FlowNode> nodes, List<String> errors) {
		Map<String, List<String>> dependencies = new HashMap<>();
		for (FlowNode node : nodes) {
			if (node == null || !"resolve".equals(normalize(node.type())) || node.config() == null) {
				continue;
			}
			Object dependsOn = node.config().get("dependsOn");
			if (dependsOn instanceof Iterable<?> iterable) {
				List<String> values = new ArrayList<>();
				for (Object dependency : iterable) {
					values.add(String.valueOf(dependency));
				}
				dependencies.put(node.id(), values);
			}
		}
		Set<String> visiting = new HashSet<>();
		Set<String> visited = new HashSet<>();
		for (String nodeId : dependencies.keySet()) {
			if (hasDependencyCycle(nodeId, dependencies, visiting, visited)) {
				errors.add("Resolver dependency graph contains a cycle at " + nodeId);
			}
		}
	}

	private boolean hasDependencyCycle(String nodeId, Map<String, List<String>> dependencies, Set<String> visiting,
			Set<String> visited) {
		if (visited.contains(nodeId)) {
			return false;
		}
		if (!visiting.add(nodeId)) {
			return true;
		}
		for (String dependency : dependencies.getOrDefault(nodeId, List.of())) {
			if (dependencies.containsKey(dependency) && hasDependencyCycle(dependency, dependencies, visiting, visited)) {
				return true;
			}
		}
		visiting.remove(nodeId);
		visited.add(nodeId);
		return false;
	}

	private void validateCondition(Map<String, Object> condition, String nodeId, List<String> errors) {
		if (condition == null || condition.isEmpty()) {
			errors.add("Branch condition is required on node " + nodeId);
			return;
		}
		String operator = String.valueOf(condition.getOrDefault("op", ""));
		if (!CONDITION_OPERATORS.contains(operator)) {
			errors.add("Unsupported condition operator " + operator + " on node " + nodeId);
		}
		Object path = condition.get("path");
		if (path != null && (!(path instanceof String text) || !text.startsWith("/"))) {
			errors.add("Condition paths must use JSON Pointer on node " + nodeId);
		}
		Object conditions = condition.get("conditions");
		if (conditions instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> nested) {
					validateCondition(castMap(nested), nodeId, errors);
				}
			}
		}
		Object nested = condition.get("condition");
		if (nested instanceof Map<?, ?> map) {
			validateCondition(castMap(map), nodeId, errors);
		}
	}

	private void validateTarget(String target, String nodeId, Set<String> nodeIds, List<String> errors) {
		if (StringUtils.hasText(target) && !nodeIds.contains(target)) {
			errors.add("Node " + nodeId + " references missing node " + target);
		}
	}

	private boolean containsForbiddenKey(Object value) {
		if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (FORBIDDEN_KEYS.contains(String.valueOf(entry.getKey())) || containsForbiddenKey(entry.getValue())) {
					return true;
				}
			}
		}
		if (value instanceof Iterable<?> iterable) {
			for (Object item : iterable) {
				if (containsForbiddenKey(item)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean hasText(Map<String, Object> config, String key) {
		return config != null && config.get(key) != null && StringUtils.hasText(String.valueOf(config.get(key)));
	}

	private String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> map ? castMap(map) : Map.of();
	}

	private List<String> strings(Object value) {
		if (!(value instanceof Iterable<?> iterable)) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (Object item : iterable) {
			if (item instanceof String text && StringUtils.hasText(text)) {
				values.add(text);
			}
		}
		return values;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> castMap(Map<?, ?> value) {
		return (Map<String, Object>) value;
	}

	private String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
	}

}
