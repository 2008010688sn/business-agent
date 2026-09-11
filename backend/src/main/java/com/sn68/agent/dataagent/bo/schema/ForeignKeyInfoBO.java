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

/**
 * 数据库外键关系业务对象。
 */
@Schema(description = "数据库外键关系业务对象")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForeignKeyInfoBO {

	@Schema(description = "源表名")
	private String table;

	@Schema(description = "源字段名")
	private String column;

	@Schema(description = "关联表名")
	private String referencedTable;

	@Schema(description = "关联字段名")
	private String referencedColumn;

}
