/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import com.sn68.agent.dataagent.dto.schema.SemanticModelImportItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 批量导入 Skill 语义模型字段映射的请求。
 */
@Schema(description = "批量导入Skill语义模型请求")
public record SkillSemanticModelBatchImportReq(
		@Schema(description = "数据源ID") @NotNull Long datasourceId,
		@Schema(description = "导入项") @NotEmpty @Valid List<SemanticModelImportItem> items) {
}
