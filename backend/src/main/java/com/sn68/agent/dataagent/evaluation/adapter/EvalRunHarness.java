/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 评估运行 harness 快照：evalMode 与候选 overlay 存在 {@code agentConfigSnapshotJson}，避免加列。
 */
public final class EvalRunHarness {

	public static final String MODE_REPLAY = "REPLAY";

	public static final String MODE_INVOKE = "INVOKE";

	public static final String KEY_EVAL_MODE = "evalMode";

	public static final String KEY_CANDIDATE_OVERLAY = "candidateOverlay";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private EvalRunHarness() {
	}

	public static String normalizeMode(String raw) {
		if (!StringUtils.hasText(raw)) {
			return MODE_REPLAY;
		}
		String mode = raw.trim().toUpperCase();
		if (MODE_INVOKE.equals(mode) || MODE_REPLAY.equals(mode)) {
			return mode;
		}
		return null;
	}

	public static boolean isInvoke(DataAgentEvalRun run, ObjectMapper objectMapper) {
		return MODE_INVOKE.equals(evalMode(run, objectMapper));
	}

	public static String evalMode(DataAgentEvalRun run, ObjectMapper objectMapper) {
		Map<String, Object> snapshot = readSnapshot(run, objectMapper);
		Object mode = snapshot.get(KEY_EVAL_MODE);
		return normalizeMode(mode == null ? null : String.valueOf(mode));
	}

	public static String overlayInstruction(DataAgentEvalRun run, ObjectMapper objectMapper) {
		Map<String, Object> snapshot = readSnapshot(run, objectMapper);
		Object overlay = snapshot.get(KEY_CANDIDATE_OVERLAY);
		if (overlay == null) {
			return null;
		}
		if (overlay instanceof Map<?, ?> map) {
			Object instruction = firstText(map.get("systemInstruction"), map.get("prompt"));
			return instruction == null ? null : String.valueOf(instruction);
		}
		try {
			JsonNode node = objectMapper.readTree(String.valueOf(overlay));
			if (node == null || node.isNull()) {
				return null;
			}
			if (node.isTextual()) {
				return node.asText();
			}
			if (node.hasNonNull("systemInstruction")) {
				return node.get("systemInstruction").asText();
			}
			if (node.hasNonNull("prompt")) {
				return node.get("prompt").asText();
			}
		}
		catch (Exception ignored) {
			return null;
		}
		return null;
	}

	public static Map<String, Object> mergeSnapshot(String existingJson, String evalMode, String candidateOverlayJson,
			ObjectMapper objectMapper) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		if (StringUtils.hasText(existingJson)) {
			try {
				Map<String, Object> existing = objectMapper.readValue(existingJson, MAP_TYPE);
				if (existing != null) {
					snapshot.putAll(existing);
				}
			}
			catch (Exception ignored) {
				snapshot.clear();
			}
		}
		if (StringUtils.hasText(evalMode)) {
			snapshot.put(KEY_EVAL_MODE, evalMode);
		}
		if (StringUtils.hasText(candidateOverlayJson)) {
			snapshot.put(KEY_CANDIDATE_OVERLAY, candidateOverlayJson);
		}
		return snapshot;
	}

	private static Map<String, Object> readSnapshot(DataAgentEvalRun run, ObjectMapper objectMapper) {
		if (run == null || !StringUtils.hasText(run.getAgentConfigSnapshotJson()) || objectMapper == null) {
			return Map.of();
		}
		try {
			Map<String, Object> snapshot = objectMapper.readValue(run.getAgentConfigSnapshotJson(), MAP_TYPE);
			return snapshot == null ? Map.of() : snapshot;
		}
		catch (Exception ex) {
			return Map.of();
		}
	}

	private static Object firstText(Object left, Object right) {
		if (left != null && StringUtils.hasText(String.valueOf(left))) {
			return left;
		}
		if (right != null && StringUtils.hasText(String.valueOf(right))) {
			return right;
		}
		return null;
	}

}
