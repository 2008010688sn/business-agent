/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建 Skill 业务知识（业务术语）的请求。
 */
@Schema(description = "创建Skill业务知识请求")
public record SkillBusinessKnowledgeCreateReq(
		@Schema(description = "业务术语") @NotBlank String businessTerm,
		@Schema(description = "描述") @NotBlank String description,
		@Schema(description = "同义词") String synonyms,
		@Schema(description = "是否参与召回") Boolean isRecall) {
}
