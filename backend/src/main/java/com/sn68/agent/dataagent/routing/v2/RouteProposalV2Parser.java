/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.routing.v2.model.RouteBindingSourceKind;
import com.sn68.agent.dataagent.routing.v2.model.RouteControlEdge;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalBinding;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalClarify;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalMode;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalStep;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalV2;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * RouteProposalV2 严格 JSON 解析器:完整解析整个 JSON 后再逐字段校验,
 * 未知字段、类型错误、重复键、尾随内容一律视为解析失败(方案第四章编译第 1 步)。
 *
 * <p>复用模块统一注入的 ObjectMapper,内部 copy 后叠加严格特性,不影响全局配置。
 */
@Component
public class RouteProposalV2Parser {

	private static final int MAX_PROPOSAL_JSON_LENGTH = 32_768;

	private static final int MAX_PARSED_STEPS = 64;

	private static final int MAX_PARSED_BINDINGS = 32;

	private static final int MAX_PARSED_CONTROL_EDGES = 64;

	private static final int MAX_STEP_KEY_LENGTH = 64;

	private static final int MAX_CAPABILITY_HANDLE_LENGTH = 128;

	private static final int MAX_TASK_LENGTH = 1000;

	private static final int MAX_PORT_HANDLE_LENGTH = 64;

	private static final int MAX_CLARIFY_QUESTION_LENGTH = 1000;

	private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "mode", "steps", "controlEdges", "clarify");

	private static final Set<String> STEP_FIELDS = Set.of("stepKey", "capabilityHandle", "task", "bindings");

	private static final Set<String> BINDING_FIELDS = Set.of("targetPortHandle", "sourceKind", "sourceStepKey",
			"sourcePortHandle");

	private static final Set<String> CLARIFY_FIELDS = Set.of("question");

	private final ObjectMapper strictMapper;

	public RouteProposalV2Parser(ObjectMapper objectMapper) {
		ObjectMapper strict = objectMapper.copy();
		strict.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		strict.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
		this.strictMapper = strict;
	}

	public RouteProposalV2 parse(String proposalJson) {
		return parse(readTree(proposalJson));
	}

	/** 完整读入整个 JSON 文档;语法错误、重复键、尾随内容均判定为 PROPOSAL_JSON_INVALID。 */
	public JsonNode readTree(String proposalJson) {
		if (!StringUtils.hasText(proposalJson)) {
			throw new PlanCompileException(PlanCompileException.PROPOSAL_JSON_INVALID, "Route proposal JSON is empty");
		}
		if (proposalJson.length() > MAX_PROPOSAL_JSON_LENGTH) {
			throw new PlanCompileException(PlanCompileException.PROPOSAL_JSON_INVALID, "Route proposal JSON is too large");
		}
		JsonNode root;
		try {
			root = strictMapper.readValue(proposalJson, JsonNode.class);
		}
		catch (JsonProcessingException ex) {
			throw new PlanCompileException(PlanCompileException.PROPOSAL_JSON_INVALID,
					"Route proposal JSON is malformed", ex);
		}
		if (root == null || !root.isObject()) {
			throw new PlanCompileException(PlanCompileException.PROPOSAL_JSON_INVALID,
					"Route proposal root must be a JSON object");
		}
		return root;
	}

	/** 对完整解析后的 JSON 树做严格 schema 校验并转为提案模型。 */
	public RouteProposalV2 parse(JsonNode root) {
		if (root == null || !root.isObject()) {
			throw schemaInvalid("Route proposal root must be a JSON object");
		}
		rejectUnknownFields(root, ROOT_FIELDS, "$");
		String schemaVersion = requiredText(root, "schemaVersion", "$", 64);
		if (!RouteProposalV2.SCHEMA_VERSION.equals(schemaVersion)) {
			throw schemaInvalid("Route proposal schemaVersion must be " + RouteProposalV2.SCHEMA_VERSION);
		}
		RouteProposalMode mode = parseMode(root);
		List<RouteProposalStep> steps = parseSteps(root.get("steps"));
		List<RouteControlEdge> controlEdges = parseControlEdges(root.get("controlEdges"));
		RouteProposalClarify clarify = parseClarify(root.get("clarify"));
		try {
			return new RouteProposalV2(schemaVersion, mode, steps, controlEdges, clarify);
		}
		catch (IllegalArgumentException ex) {
			throw schemaInvalid(ex.getMessage());
		}
	}

	private RouteProposalMode parseMode(JsonNode root) {
		String mode = requiredText(root, "mode", "$", 32);
		try {
			return RouteProposalMode.valueOf(mode);
		}
		catch (IllegalArgumentException ex) {
			throw schemaInvalid("$.mode '" + mode + "' is not a supported route proposal mode");
		}
	}

	private List<RouteProposalStep> parseSteps(JsonNode node) {
		if (isMissing(node)) {
			return List.of();
		}
		if (!node.isArray() || node.size() > MAX_PARSED_STEPS) {
			throw schemaInvalid("$.steps must be a bounded JSON array");
		}
		List<RouteProposalStep> steps = new ArrayList<>(node.size());
		for (int i = 0; i < node.size(); i++) {
			steps.add(parseStep(node.get(i), i));
		}
		return steps;
	}

	private RouteProposalStep parseStep(JsonNode node, int index) {
		String path = "$.steps[" + index + "]";
		if (node == null || !node.isObject()) {
			throw schemaInvalid(path + " must be a JSON object");
		}
		rejectUnknownFields(node, STEP_FIELDS, path);
		String stepKey = requiredText(node, "stepKey", path, MAX_STEP_KEY_LENGTH);
		String capabilityHandle = requiredText(node, "capabilityHandle", path, MAX_CAPABILITY_HANDLE_LENGTH);
		String task = requiredText(node, "task", path, MAX_TASK_LENGTH);
		List<RouteProposalBinding> bindings = parseBindings(node.get("bindings"), path + ".bindings");
		try {
			return new RouteProposalStep(stepKey, capabilityHandle, task, bindings);
		}
		catch (IllegalArgumentException ex) {
			throw schemaInvalid(path + ": " + ex.getMessage());
		}
	}

	private List<RouteProposalBinding> parseBindings(JsonNode node, String path) {
		if (isMissing(node)) {
			return List.of();
		}
		if (!node.isArray() || node.size() > MAX_PARSED_BINDINGS) {
			throw schemaInvalid(path + " must be a bounded JSON array");
		}
		List<RouteProposalBinding> bindings = new ArrayList<>(node.size());
		for (int i = 0; i < node.size(); i++) {
			bindings.add(parseBinding(node.get(i), path + "[" + i + "]"));
		}
		return bindings;
	}

	private RouteProposalBinding parseBinding(JsonNode node, String path) {
		if (node == null || !node.isObject()) {
			throw schemaInvalid(path + " must be a JSON object");
		}
		rejectUnknownFields(node, BINDING_FIELDS, path);
		String targetPortHandle = requiredText(node, "targetPortHandle", path, MAX_PORT_HANDLE_LENGTH);
		String sourceKindText = requiredText(node, "sourceKind", path, 32);
		RouteBindingSourceKind sourceKind;
		try {
			sourceKind = RouteBindingSourceKind.valueOf(sourceKindText);
		}
		catch (IllegalArgumentException ex) {
			throw schemaInvalid(path + ".sourceKind '" + sourceKindText + "' is not a supported source kind");
		}
		String sourceStepKey = optionalText(node, "sourceStepKey", path, MAX_STEP_KEY_LENGTH);
		String sourcePortHandle = optionalText(node, "sourcePortHandle", path, MAX_PORT_HANDLE_LENGTH);
		try {
			return new RouteProposalBinding(targetPortHandle, sourceKind, sourceStepKey, sourcePortHandle);
		}
		catch (IllegalArgumentException ex) {
			throw schemaInvalid(path + ": " + ex.getMessage());
		}
	}

	private List<RouteControlEdge> parseControlEdges(JsonNode node) {
		if (isMissing(node)) {
			return List.of();
		}
		if (!node.isArray() || node.size() > MAX_PARSED_CONTROL_EDGES) {
			throw schemaInvalid("$.controlEdges must be a bounded JSON array");
		}
		List<RouteControlEdge> edges = new ArrayList<>(node.size());
		for (int i = 0; i < node.size(); i++) {
			JsonNode edge = node.get(i);
			String path = "$.controlEdges[" + i + "]";
			if (edge == null || !edge.isArray() || edge.size() != 2 || !edge.get(0).isTextual()
					|| !edge.get(1).isTextual()) {
				throw schemaInvalid(path + " must be a [fromStepKey, toStepKey] string pair");
			}
			try {
				edges.add(new RouteControlEdge(edge.get(0).textValue(), edge.get(1).textValue()));
			}
			catch (IllegalArgumentException ex) {
				throw schemaInvalid(path + ": " + ex.getMessage());
			}
		}
		return edges;
	}

	private RouteProposalClarify parseClarify(JsonNode node) {
		if (isMissing(node)) {
			return null;
		}
		if (!node.isObject()) {
			throw schemaInvalid("$.clarify must be null or a JSON object");
		}
		rejectUnknownFields(node, CLARIFY_FIELDS, "$.clarify");
		String question = requiredText(node, "question", "$.clarify", MAX_CLARIFY_QUESTION_LENGTH);
		return new RouteProposalClarify(question);
	}

	private void rejectUnknownFields(JsonNode node, Set<String> allowedFields, String path) {
		Iterator<String> names = node.fieldNames();
		while (names.hasNext()) {
			String name = names.next();
			if (!allowedFields.contains(name)) {
				throw schemaInvalid("Unknown field '" + name + "' at " + path);
			}
		}
	}

	private String requiredText(JsonNode owner, String field, String path, int maxLength) {
		String text = optionalText(owner, field, path, maxLength);
		if (text == null) {
			throw schemaInvalid(path + "." + field + " is required");
		}
		return text;
	}

	private String optionalText(JsonNode owner, String field, String path, int maxLength) {
		JsonNode node = owner.get(field);
		if (isMissing(node)) {
			return null;
		}
		if (!node.isTextual()) {
			throw schemaInvalid(path + "." + field + " must be a string");
		}
		String text = node.textValue().trim();
		if (text.isEmpty()) {
			throw schemaInvalid(path + "." + field + " must not be blank");
		}
		if (text.length() > maxLength) {
			throw schemaInvalid(path + "." + field + " exceeds max length " + maxLength);
		}
		return text;
	}

	private static boolean isMissing(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull();
	}

	private static PlanCompileException schemaInvalid(String message) {
		return new PlanCompileException(PlanCompileException.PROPOSAL_SCHEMA_INVALID, message);
	}

}
