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

import com.alibaba.excel.annotation.ExcelProperty;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 语义模型导入项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "语义模型IMportItem实体")
public class SemanticModelImportItem {

	@Schema(description = "数据表名称")
	@NotBlank(message = "表名不能为空")
	@ExcelProperty(value = "表名*", index = 0)
	private String tableName;

	@Schema(description = "字段名称")
	@NotBlank(message = "字段名不能为空")
	@ExcelProperty(value = "字段名*", index = 1)
	private String columnName;

	@Schema(description = "名称")
	@NotBlank(message = "业务名称不能为空")
	@ExcelProperty(value = "业务名称*", index = 2)
	private String businessName;

	@Schema(description = "同义词")
	@ExcelProperty(value = "同义词", index = 4)
	private String synonyms;

	@Schema(description = "业务描述")
	@JsonAlias({ "businessDesc", "description", "desc" })
	@ExcelProperty(value = "业务描述", index = 5)
	private String businessDescription;

	@Schema(description = "字段注释")
	@ExcelProperty(value = "字段注释", index = 6)
	private String columnComment;

	@Schema(description = "数据类型")
	@NotBlank(message = "数据类型不能为空")
	@ExcelProperty(value = "数据类型*", index = 3)
	private String dataType;

	/**
	 * 创建时间（可选，用于导入时指定创建时间）
	 */
	@Schema(description = "创建时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@ExcelProperty(value = "创建时间", index = 7)
	private Instant createTime;

}
