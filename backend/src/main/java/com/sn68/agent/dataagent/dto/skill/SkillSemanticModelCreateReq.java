/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建 Skill 语义模型字段映射的请求。
 */
@Schema(description = "创建Skill语义模型请求")
public record SkillSemanticModelCreateReq(
		@Schema(description = "数据源ID") @NotNull Long datasourceId,
		@Schema(description = "表名") @NotBlank String tableName,
		@Schema(description = "字段名") @NotBlank String columnName,
		@Schema(description = "业务名称") @NotBlank String businessName,
		@Schema(description = "同义词") String synonyms,
		@Schema(description = "业务描述") String businessDescription,
		@Schema(description = "字段注释") String columnComment,
		@Schema(description = "数据类型") @NotBlank String dataType) {
}
