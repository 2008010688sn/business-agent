/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.ui;

import java.util.List;
import java.util.Map;

/**
 * 统一的 Agent UI 消息结构，持久化消息与流式推送消息共用同一份 Schema。
 */
public record AgentUiMessage(String schemaVersion, String kind, String runtimeRequestId, Source source, Content content,
		Payload payload, List<Action> actions, Timing timing, List<Step> steps) {

	public AgentUiMessage(String schemaVersion, String kind, String runtimeRequestId, Source source, Content content,
			Payload payload, List<Action> actions, Timing timing) {
		this(schemaVersion, kind, runtimeRequestId, source, content, payload, actions, timing, List.of());
	}

	public static final String SCHEMA_VERSION = "agent-ui/v2";

	public static final String KIND_SKILL_FLOW = "skill-flow";

	public static final String KIND_ANALYSIS_RESULT = "analysis-result";

	public static final String KIND_TOOL_CONFIRM = "tool-confirm";

	/**
	 * 消息来源定位信息（Agent/Skill/流程节点）。
	 */
	public record Source(String agentId, String skillCode, String flowInstanceId, String nodeId) {
	}

	/**
	 * 消息正文内容与格式。
	 */
	public record Content(String format, String text) {
	}

	/**
	 * 交互类消息的动作载荷（表单值与候选项）。
	 */
	public record Payload(String action, Map<String, Object> values, List<Map<String, Object>> options) {
	}

	/**
	 * 消息上可执行的单个交互动作。
	 */
	public record Action(String actionId, String type, String label, Object value, Map<String, Object> payload) {
	}

	/**
	 * 消息对应执行阶段的耗时信息。
	 */
	public record Timing(String stageCode, Long durationMs) {
	}

	/**
	 * FLOW 回合步骤条目：kind ∈ tool|node|state，label 为展示名，status/durationMs 与进度事件一致。
	 */
	public record Step(String kind, String label, String status, Long durationMs) {
	}

}
