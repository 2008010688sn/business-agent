/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Agent 与 Skill 的锁定版本绑定关系。
 */
@Schema(description = "Agent与Skill绑定关系")
public record AgentSkillBindingV2DTO(
		@Schema(description = "Skill ID") Long skillId,
		@Schema(description = "锁定的Skill版本ID") Long pinnedSkillVersionId,
		@Schema(description = "绑定优先级") Integer priority,
		@Schema(description = "是否启用") Boolean enabled) {
}
