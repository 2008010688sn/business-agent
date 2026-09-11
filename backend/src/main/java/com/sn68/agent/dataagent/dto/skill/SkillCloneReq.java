/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 从已发布 Skill 创建新草稿的请求。
 */
@Schema(description = "Skill克隆请求")
public record SkillCloneReq(
		@Schema(description = "新Skill编码") String skillCode,
		@Schema(description = "新Skill名称") String skillName) {
}
