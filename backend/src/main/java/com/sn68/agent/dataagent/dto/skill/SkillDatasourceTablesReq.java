/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 设置 Skill 数据源可见数据表白名单的请求。
 */
@Schema(description = "Skill数据源表白名单请求")
public record SkillDatasourceTablesReq(@Schema(description = "数据表列表") List<String> tables) {
}
