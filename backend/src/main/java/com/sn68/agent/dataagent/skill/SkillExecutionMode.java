/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * Skill 执行模式。
 */
public enum SkillExecutionMode implements DictEnum<String> {

	KNOWLEDGE("KNOWLEDGE", "知识问答"),

	DETERMINISTIC("DETERMINISTIC", "确定性查询"),

	REACT("REACT", "模型工具调用"),

	FLOW("FLOW", "确定性流程");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	SkillExecutionMode(String value, String label) {
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
