/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 单个草稿 Skill 结构化路由规则的确定性测试结果。
 */
@Schema(description = "Skill草稿路由规则测试结果")
public record SkillRouteTestResult(
		@Schema(description = "是否命中路由") boolean matched,
		@Schema(description = "Skill编码") String skillCode,
		@Schema(description = "命中/未命中原因编码") String reasonCode,
		@Schema(description = "词法匹配得分") int lexicalScore,
		@Schema(description = "是否精确匹配") boolean exact,
		@Schema(description = "是否被排除规则命中") boolean excluded,
		@Schema(description = "命中的路由信号列表") List<String> matchedSignals) {

	public SkillRouteTestResult {
		matchedSignals = matchedSignals == null ? List.of() : List.copyOf(matchedSignals);
	}
}
