/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * 已处理 Skill 对当前 Turn 的最终语义。枚举值恒等于 name，仅补充中文标签。
 */
public enum SkillExecutionOutcome implements DictEnum<String> {

	WAITING("等待中"),

	SUCCEEDED("成功"),

	FAILED("失败");

	private final String label;

	SkillExecutionOutcome(String label) {
		this.label = label;
	}

	@Override
	public String getValue() {
		return name();
	}

	@Override
	public String getLabel() {
		return label;
	}

}
