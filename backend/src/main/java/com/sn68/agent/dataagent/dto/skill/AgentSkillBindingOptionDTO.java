/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Agent 绑定编辑器可选的 Skill 轻量选项。
 */
@Schema(description = "Agent绑定编辑器Skill选项")
public record AgentSkillBindingOptionDTO(
		@Schema(description = "Skill ID") Long skillId,
		@Schema(description = "Skill编码") String skillCode,
		@Schema(description = "Skill名称") String skillName,
		@Schema(description = "Skill描述") String description,
		@Schema(description = "执行模式") String executionMode,
		@Schema(description = "Skill状态") String status,
		@Schema(description = "展示顺序") Integer displayOrder,
		@Schema(description = "当前已发布版本ID") Long publishedVersionId,
		@Schema(description = "是否可选") boolean selectable,
		@Schema(description = "不可选原因") String unavailableReason,
		@Schema(description = "已发布版本选项列表") List<AgentSkillPublishedVersionOptionDTO> publishedVersions) {
}
