/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill;

import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * Skill 可见范围。枚举值恒等于 name，仅补充中文标签。
 */
public enum SkillScope implements DictEnum<String> {

	TENANT("租户级"),

	/**
	 * 平台级 Skill：由能力市场（skill_market_listing）审核发布，作为租户安装的来源版本。
	 * 租户侧技能中心的 normalizeScope 仍会把外部传入的 PLATFORM 归一为 TENANT，
	 * 租户不能自行声明平台范围，平台条目只经市场审核流产生。
	 */
	PLATFORM("平台级");

	private final String label;

	SkillScope(String label) {
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
