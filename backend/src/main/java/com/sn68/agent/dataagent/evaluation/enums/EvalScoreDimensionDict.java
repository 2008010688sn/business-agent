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
 * Agent 评估评分维度。
 */
public enum EvalScoreDimensionDict implements DictEnum<String> {

	QUALITY("quality", "质量"),

	SAFETY("safety", "安全"),

	EFFICIENCY("efficiency", "效率"),

	STABILITY("stability", "稳定性"),

	TOOL_TRACE("tool_trace", "工具轨迹"),

	SQL_SAFETY("sql_safety", "SQL 安全"),

	KNOWLEDGE_GROUNDING("knowledge_grounding", "知识依据");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	EvalScoreDimensionDict(String value, String label) {
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
