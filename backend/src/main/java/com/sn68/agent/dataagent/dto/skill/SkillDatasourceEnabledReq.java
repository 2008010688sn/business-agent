/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 启用或停用 Skill 已绑定数据源的请求。
 */
@Schema(description = "Skill数据源启停请求")
public record SkillDatasourceEnabledReq(@Schema(description = "是否启用") @NotNull Boolean enabled) {
}
