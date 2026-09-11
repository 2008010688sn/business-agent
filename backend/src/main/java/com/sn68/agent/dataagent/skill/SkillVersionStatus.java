/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill;

import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * Skill 版本生命周期状态。枚举值恒等于 name，仅补充中文标签。
 */
public enum SkillVersionStatus implements DictEnum<String> {

	DRAFT("草稿"),

	PUBLISHED("已发布"),

	RETIRED("已下线");

	private final String label;

	SkillVersionStatus(String label) {
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
