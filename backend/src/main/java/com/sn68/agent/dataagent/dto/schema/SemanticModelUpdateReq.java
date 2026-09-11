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
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 语义模型更新 DTO，仅允许编辑业务解释字段。 */
/**
 * 语义模型Update数据传输对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "语义模型Update数据传输对象")
public class SemanticModelUpdateReq {

	@Schema(description = "名称")
	@NotBlank(message = "业务名称不能为空")
	private String businessName;

	@Schema(description = "同义词")
	private String synonyms;

	@Schema(description = "业务描述")
	private String businessDescription;

	@Schema(description = "字段注释")
	private String columnComment;

	@Schema(description = "数据类型")
	@NotBlank(message = "数据类型不能为空")
	private String dataType;

}
