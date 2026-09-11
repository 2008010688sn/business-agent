/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * Agent 评估慢请求原因。
 */
public enum EvalSlowReasonDict implements DictEnum<String> {

	MODEL_CALL_SLOW("MODEL_CALL_SLOW", "模型调用慢"),

	TOOL_SLOW("TOOL_SLOW", "工具调用慢"),

	TOOL_TOO_MANY("TOOL_TOO_MANY", "工具调用次数过多"),

	TOKEN_TOO_HIGH("TOKEN_TOO_HIGH", "Token 消耗过高"),

	RAG_SLOW("RAG_SLOW", "知识检索慢"),

	SQL_GUARD_SLOW("SQL_GUARD_SLOW", "SQL 守卫慢"),

	ORCHESTRATION_SLOW("ORCHESTRATION_SLOW", "编排链路慢"),

	PERSIST_OVERHEAD("PERSIST_OVERHEAD", "持久化开销高");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	EvalSlowReasonDict(String value, String label) {
		this.value = value;
		this.label = label;
	}

	@Override
	public String getValue() {
		return value;
	}

	@Override
	public String getLabel() {
		return label;
	}

}
