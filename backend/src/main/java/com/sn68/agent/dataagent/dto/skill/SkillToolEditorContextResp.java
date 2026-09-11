/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Skill 工具引用编辑器所需的最小上下文数据。
 */
@Schema(description = "Skill工具引用编辑器上下文")
public record SkillToolEditorContextResp(
		@Schema(description = "当前工具引用列表") List<SkillToolRefSaveDTO> refs,
		@Schema(description = "可选工具版本选项列表") List<ToolVersionOptionDTO> options) {
}
