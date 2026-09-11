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
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据库 Schema 结构描述：数据表、外键与统计信息。
 */
@Data
@NoArgsConstructor
@Schema(description = "数据库Schema结构")
public class SchemaDTO {

	@Schema(description = "Schema名称")
	private String name;

	@Schema(description = "Schema描述")
	private String description;

	@Schema(description = "数据表数量")
	private Integer tableCount;

	@Schema(description = "数据表列表")
	private List<TableDTO> table;

	@Schema(description = "外键关系列表")
	private List<String> foreignKeys;

	@Override
	public String toString() {
		return "SchemaDTO{" + "name='" + name + '\'' + ", description='" + description + '\'' + ", tableCount="
				+ tableCount + ", table=" + table + ", foreignKeys=" + foreignKeys + '}';
	}

}
