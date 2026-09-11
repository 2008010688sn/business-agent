/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill;

import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * Skill 业务种类：决定路由与执行模式的可选范围。枚举值恒等于 name（落库/路由均按 name 匹配），仅补充中文标签。
 */
public enum SkillKind implements DictEnum<String> {

	QUERY("数据查询"),

	QA("知识问答"),

	ACTION("业务动作"),

	ORCHESTRATION("多技能编排");

	private final String label;

	SkillKind(String label) {
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
