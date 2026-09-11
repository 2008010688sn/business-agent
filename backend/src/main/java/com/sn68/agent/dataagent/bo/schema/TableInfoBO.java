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
package com.sn68.agent.dataagent.bo.schema;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 数据库表元数据业务对象。
 */
@Schema(description = "数据库表元数据业务对象")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableInfoBO {

	@Schema(description = "所属 Schema")
	private String schema;

	@Schema(description = "表名")
	private String name;

	@Schema(description = "表说明")
	private String description;

	@Schema(description = "表类型")
	private String type;

	@Schema(description = "外键关系说明")
	private String foreignKey;

	@Schema(description = "主键字段列表")
	private List<String> primaryKeys;

	@Schema(description = "字段元数据列表")
	private List<ColumnInfoBO> columns;

}
