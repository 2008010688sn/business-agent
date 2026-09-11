/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Skill 工具引用的可编辑字段。
 */
@Schema(description = "Skill工具引用保存项")
public record SkillToolRefSaveDTO(
		@Schema(description = "锁定的工具版本ID") Long resourceVersionId,
		@Schema(description = "工具用途说明") String usage,
		@Schema(description = "展示顺序") Integer displayOrder) {
}
