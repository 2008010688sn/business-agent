/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 原子化整体替换 Agent 的全部 Skill 绑定的请求。
 */
@Schema(description = "Agent Skill绑定整体更新请求")
public record AgentSkillBindingsUpdateReq(
		@Schema(description = "更新后的完整绑定列表") List<AgentSkillBindingV2DTO> bindings) {
}
