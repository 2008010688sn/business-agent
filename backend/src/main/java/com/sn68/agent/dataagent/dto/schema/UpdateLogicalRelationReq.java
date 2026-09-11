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
import lombok.Data;

/**
 * UpdateLogicalRelation数据传输对象。
 */
@Data
@Schema(description = "更新逻辑外键请求")
public class UpdateLogicalRelationReq {

	@Schema(description = "逻辑关系ID")
	private Long id;

	@Schema(description = "主表名")
	private String sourceTableName;

	@Schema(description = "主表字段名")
	private String sourceColumnName;

	@Schema(description = "关联表名")
	private String targetTableName;

	@Schema(description = "关联表字段名")
	private String targetColumnName;

	@Schema(description = "关系类型")
	private String relationType;

	@Schema(description = "业务描述")
	private String description;

}
