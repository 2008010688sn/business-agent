/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import com.sn68.agent.dataagent.dto.datasource.TableColumnsSelectionDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.List;

/**
 * 设置 Skill 数据源可见字段白名单的请求。
 */
@Schema(description = "Skill数据源字段白名单请求")
public record SkillDatasourceColumnsReq(
		@Schema(description = "表字段白名单") @Valid List<TableColumnsSelectionDTO> tables) {
}
