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
package com.sn68.agent.dataagent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话Context用量展示对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话Context用量展示对象")
public class SessionContextUsageVO {

	@Schema(description = "Token")
	private Long usedTokens;

	@Schema(description = "限制Token")
	private Long limitTokens;

	@Schema(description = "用量字段")
	private Double usageRatio;

	@Schema(description = "消息数量")
	private Integer messageCount;

	@Schema(description = "模型名称")
	private Boolean estimated;

	@Schema(description = "模型名称")
	private String modelName;

	@Schema(description = "对话模型配置ID")
	private Long chatModelConfigId;

}
