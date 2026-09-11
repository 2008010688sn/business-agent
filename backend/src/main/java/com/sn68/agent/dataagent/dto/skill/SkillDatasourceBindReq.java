/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Skill 绑定数据源的请求。
 */
@Schema(description = "Skill数据源绑定请求")
public record SkillDatasourceBindReq(@Schema(description = "数据源ID") @NotNull Long datasourceId) {
}
