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
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 添加语义模型的DTO类 */
/**
 * 语义模型Add数据传输对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "语义模型Add数据传输对象")
public class SemanticModelAddDTO {

	/** 关联的Skill ID */
	@Schema(description = "Skill ID")
	@NotNull(message = "Skill ID不能为空")
	private Long skillId;

	@Schema(description = "数据源ID")
	@NotNull(message = "数据源ID不能为空")
	private Long datasourceId;

	/** 关联的表名 */
	@Schema(description = "数据表名称")
	@NotBlank(message = "表名不能为空")
	private String tableName;

	/** 数据库中的物理字段名 (例如: csat_score) */
	@Schema(description = "字段名称")
	@NotBlank(message = "数据库字段名不能为空")
	private String columnName;

	/** 业务名/别名 (例如: 客户满意度分数) */
	@Schema(description = "名称")
	@NotBlank(message = "业务名称不能为空")
	private String businessName;

	/** 业务名的同义词 (例如: 满意度,客户评分) */
	@Schema(description = "同义词")
	private String synonyms;

	/** 业务描述 (用于向LLM解释字段的业务含义) */
	@Schema(description = "业务描述")
	private String businessDescription;

	/** 数据库中的物理字段的原始注释 */
	@Schema(description = "字段注释")
	private String columnComment;

	/** 物理数据类型 (例如: int, varchar(20)) */
	@Schema(description = "数据类型")
	@NotBlank(message = "数据类型不能为空")
	private String dataType;

}
