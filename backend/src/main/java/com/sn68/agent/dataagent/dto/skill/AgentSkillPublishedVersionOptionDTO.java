/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Agent 绑定编辑器展示的已发布 Skill 版本最小信息。
 */
@Schema(description = "已发布Skill版本选项")
public record AgentSkillPublishedVersionOptionDTO(
		@Schema(description = "Skill版本ID") Long id,
		@Schema(description = "版本号") Integer versionNo,
		@Schema(description = "Skill名称") String skillName,
		@Schema(description = "Skill描述") String description,
		@Schema(description = "Skill类型") String skillKind,
		@Schema(description = "执行模式") String executionMode) {

	public AgentSkillPublishedVersionOptionDTO(Long id, Integer versionNo) {
		this(id, versionNo, null, null, null, null);
	}

	public AgentSkillPublishedVersionOptionDTO(Long id, Integer versionNo, String skillKind, String executionMode) {
		this(id, versionNo, null, null, skillKind, executionMode);
	}
}
