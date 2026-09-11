/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Skill 导入请求，载荷为技能中心导出的可移植 Skill 包。
 */
@Schema(description = "Skill导入请求")
public record SkillImportReq(
		@Schema(description = "技能中心导出的Skill包内容") Map<String, Object> bundle) {
}
