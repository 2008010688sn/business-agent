/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 修改 Skill 业务知识（业务术语）的请求。
 */
@Schema(description = "修改Skill业务知识请求")
public record SkillBusinessKnowledgeUpdateReq(
		@Schema(description = "业务术语") @NotBlank String businessTerm,
		@Schema(description = "描述") @NotBlank String description,
		@Schema(description = "同义词") String synonyms) {
}
