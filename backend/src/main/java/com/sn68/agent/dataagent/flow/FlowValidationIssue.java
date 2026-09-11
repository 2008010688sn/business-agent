/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可定位到 FLOW 配置路径或节点的结构化校验问题。
 */
public record FlowValidationIssue(String code, String path, String nodeId, String severity, String message) {

	private static final Pattern DUPLICATE_NODE_PATTERN = Pattern.compile("Duplicate FLOW node id:\\s*([^\\s:]+)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern NODE_PATTERN = Pattern.compile("(?:on\\s+\\bnode\\b|\\bnode\\b|节点)\\s+([^\\s:]+)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern TRAILING_NODE_PATTERN = Pattern.compile("\\bon\\s+([^\\s:]+)$",
			Pattern.CASE_INSENSITIVE);

	public static FlowValidationIssue error(String message) {
		String safeMessage = message == null ? "FLOW 配置无效" : message;
		String nodeId = extractNodeId(safeMessage);
		String path = issuePath(safeMessage, nodeId);
		return new FlowValidationIssue("FLOW_DEFINITION_INVALID", path, nodeId, "ERROR", safeMessage);
	}

	private static String extractNodeId(String message) {
		Matcher duplicate = DUPLICATE_NODE_PATTERN.matcher(message);
		if (duplicate.find()) {
			return duplicate.group(1);
		}
		Matcher matcher = NODE_PATTERN.matcher(message);
		while (matcher.find()) {
			String candidate = matcher.group(1);
			if (!"must".equalsIgnoreCase(candidate) && !"id".equalsIgnoreCase(candidate)
					&& !"on".equalsIgnoreCase(candidate)) {
				return candidate;
			}
		}
		Matcher trailing = TRAILING_NODE_PATTERN.matcher(message);
		return trailing.find() ? trailing.group(1) : null;
	}

	private static String issuePath(String message, String nodeId) {
		if (message.contains("schemaVersion")) {
			return "flowDefinition.schemaVersion";
		}
		if (message.contains("startNode")) {
			return "flowDefinition.startNode";
		}
		if (message.contains("variablesSchema") || message.contains("x-temporal")) {
			return "flowDefinition.variablesSchema";
		}
		String nodePath = nodeId == null ? "flowDefinition" : "flowDefinition.nodes." + nodeId;
		if (message.contains("uiActions")) {
			return nodePath + ".config.uiActions";
		}
		if (message.contains("literalMappings")) {
			return nodePath + ".config.literalMappings";
		}
		if (message.contains("collectionPresentation")) {
			return nodePath + ".config.collectionPresentation";
		}
		if (message.contains("textSearch")) {
			return nodePath + ".config.textSearch";
		}
		if (message.contains("extraction")) {
			return nodePath + ".config.extraction";
		}
		if (message.contains("successCondition")) {
			return nodePath + ".config.successCondition";
		}
		if (message.contains("cancelPolicy")) {
			return nodePath + ".config.cancelPolicy";
		}
		if (message.contains("condition")) {
			return nodePath + ".branches";
		}
		return nodePath;
	}
}
