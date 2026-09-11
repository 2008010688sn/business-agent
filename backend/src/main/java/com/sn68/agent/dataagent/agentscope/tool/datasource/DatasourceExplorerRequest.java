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
package com.sn68.agent.dataagent.agentscope.tool.datasource;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

/**
 * 数据源探索工具请求参数。
 */
@Schema(description = "数据源探索工具请求参数")
@Data
public class DatasourceExplorerRequest {

	@Schema(description = "探索动作")
	private DatasourceExplorerAction action;

	@Schema(description = "用户查询")
	private String query;

	@Schema(description = "表名")
	private String tableName;

	@Schema(description = "SQL 语句")
	private String sql;

	@Schema(description = "返回数量限制")
	private Integer limit;

	@Schema(description = "返回明细级别，默认 COMPACT")
	private DatasourceExplorerDetailLevel detailLevel = DatasourceExplorerDetailLevel.COMPACT;

	@JsonIgnore
	@Schema(hidden = true)
	private boolean requireAuthorizedBaseTable;

	@JsonIgnore
	@Schema(hidden = true)
	private Integer statementTimeoutSeconds;

	@JsonIgnore
	@Schema(hidden = true)
	private boolean skipPhysicalRelationMetadata;

	@JsonIgnore
	@Schema(hidden = true)
	private Runnable staticGuardPassedCallback;

	public DatasourceExplorerDetailLevel effectiveDetailLevel() {
		return detailLevel == null ? DatasourceExplorerDetailLevel.COMPACT : detailLevel;
	}

}
