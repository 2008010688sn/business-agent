/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * 持久运行时预算消耗类型，决定 amount 的计量单位。
 */
public enum RuntimeBudgetType implements DictEnum<String> {

	MODEL_CALL("MODEL_CALL", "模型调用次数"),

	TOOL_CALL("TOOL_CALL", "工具调用次数"),

	PROMPT_TOKENS("PROMPT_TOKENS", "输入Token数"),

	COMPLETION_TOKENS("COMPLETION_TOKENS", "输出Token数"),

	DURATION_MS("DURATION_MS", "耗时毫秒"),

	COST("COST", "成本金额");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	RuntimeBudgetType(String value, String label) {
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
