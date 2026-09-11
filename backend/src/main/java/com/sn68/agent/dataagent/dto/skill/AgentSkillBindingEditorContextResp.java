/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Agent Skill 绑定编辑器所需的最小上下文数据。
 */
@Schema(description = "Agent Skill绑定编辑器上下文")
public record AgentSkillBindingEditorContextResp(
		@Schema(description = "当前绑定列表") List<AgentSkillBindingV2DTO> bindings,
		@Schema(description = "可选Skill选项列表") List<AgentSkillBindingOptionDTO> skills,
		@Schema(description = "绑定问题提示列表") List<String> issues) {
}
