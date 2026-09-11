/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.dto.routing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Skill 路由规则的完整响应契约。
 */
@Schema(description = "Skill路由规则")
public record RouteRulesDTO(
		@Schema(description = "精确匹配词列表") List<String> exact,
		@Schema(description = "短语匹配列表") List<String> phrases,
		@Schema(description = "别名列表") List<String> aliases,
		@Schema(description = "正向示例列表") List<String> positiveExamples,
		@Schema(description = "正向正则模式列表") List<String> positivePatterns,
		@Schema(description = "负向示例列表") List<String> negativeExamples,
		@Schema(description = "硬排除词列表") List<String> hardExcludes,
		@Schema(description = "硬排除正则模式列表") List<String> hardExcludePatterns,
		@Schema(description = "是否允许FLOW自动选择") boolean allowFlowAutoSelect) {

	public RouteRulesDTO {
		exact = copy(exact);
		phrases = copy(phrases);
		aliases = copy(aliases);
		positiveExamples = copy(positiveExamples);
		positivePatterns = copy(positivePatterns);
		negativeExamples = copy(negativeExamples);
		hardExcludes = copy(hardExcludes);
		hardExcludePatterns = copy(hardExcludePatterns);
	}

	private static List<String> copy(List<String> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

}
