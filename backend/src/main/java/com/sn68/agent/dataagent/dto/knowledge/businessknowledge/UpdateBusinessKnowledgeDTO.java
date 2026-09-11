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
package com.sn68.agent.dataagent.dto.knowledge.businessknowledge;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Update业务知识数据传输对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Update业务知识数据传输对象")
public class UpdateBusinessKnowledgeDTO {

	@Schema(description = "业务术语")
	@NotBlank(message = "Business term cannot be empty")
	private String businessTerm;

	@Schema(description = "描述")
	@NotBlank(message = "Description cannot be empty")
	private String description;

	// Synonyms, comma separated
	@Schema(description = "同义词")
	private String synonyms;

	@Schema(description = "Skill ID")
	@NotNull(message = "Skill ID cannot be Null")
	private Long skillId;

}
