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
package com.sn68.agent.dataagent.dto.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语义模型批量导入请求：按数据源批量写入字段语义映射。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "语义模型批量导入请求")
public class SemanticModelBatchImportDTO {

	@Schema(description = "Skill ID")
	@NotNull(message = "Skill ID不能为空")
	private Long skillId;

	@Schema(description = "数据源ID")
	@NotNull(message = "数据源ID不能为空")
	private Long datasourceId;

	@Schema(description = "导入项列表")
	@NotEmpty(message = "导入数据不能为空")
	@Valid
	private List<SemanticModelImportItem> items;

}
