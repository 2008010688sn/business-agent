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
package com.sn68.agent.dataagent.market.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * 能力市场条目提交审核请求：对引用的 Skill 版本做不可变快照后进入 REVIEWING。
 */
@Builder
@Schema(description = "能力市场条目提交审核请求")
public record SkillMarketSubmitReviewReq(
		@Schema(description = "引用的 Skill 版本ID（data_agent_skill_version.id，须为已发布版本）")
		@NotNull(message = "skillVersionId不能为空") Long skillVersionId,
		@Schema(description = "变更说明") String changeNote
) {
}
