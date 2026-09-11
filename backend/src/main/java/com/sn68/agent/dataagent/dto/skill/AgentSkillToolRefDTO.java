/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Skill 与锁定工具版本的引用关系。
 */
@Schema(description = "Skill工具版本引用")
public record AgentSkillToolRefDTO(
		@Schema(description = "引用ID") Long id,
		@Schema(description = "Skill编码") String skillId,
		@Schema(description = "Skill版本ID") Long skillVersionId,
		@Schema(description = "工具资源Key") String resourceKey,
		@Schema(description = "锁定的工具版本ID") Long resourceVersionId,
		@Schema(description = "工具用途说明") String usage,
		@Schema(description = "引用状态") String status,
		@Schema(description = "展示顺序") Integer displayOrder,
		@Schema(description = "扩展配置") Map<String, Object> extConfig) {
}
