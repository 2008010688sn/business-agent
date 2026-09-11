/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import com.sn68.agent.dataagent.flow.FlowValidationIssue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Skill 草稿配置校验结果（含错误、警告与结构化问题明细）。
 */
@Schema(description = "Skill草稿校验结果")
public record SkillValidationResult(
		@Schema(description = "是否校验通过") boolean valid,
		@Schema(description = "错误信息列表") List<String> errors,
		@Schema(description = "警告信息列表") List<String> warnings,
		@Schema(description = "结构化校验问题明细") List<FlowValidationIssue> issues) {

	public SkillValidationResult(boolean valid, List<String> errors, List<String> warnings) {
		this(valid, errors, warnings, errors == null ? List.of() : errors.stream().map(FlowValidationIssue::error).toList());
	}
}
