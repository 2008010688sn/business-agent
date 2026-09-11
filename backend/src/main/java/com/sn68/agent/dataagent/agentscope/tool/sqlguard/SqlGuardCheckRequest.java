/*
 * Copyright 2026 the original author or authors.
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
package com.sn68.agent.dataagent.agentscope.tool.sqlguard;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

/**
 * SQL 守护工具入参：校验动作（SQL_VERIFY/DATA_PROFILE）、用户 query、待校验 SQL 及画像目标等。
 */
@Schema(description = "SQL 守护校验请求")
@Data
class SqlGuardCheckRequest {

	@Schema(description = "校验动作")
	private String action;

	@Schema(description = "用户查询")
	private String query;

	@Schema(description = "待校验 SQL")
	private String sql;

	@Schema(description = "人工反馈内容")
	private String humanFeedbackContent;

	@Schema(description = "目标表名")
	private String tableName;

	@Schema(description = "目标字段名列表")
	private List<String> columnNames;

	@Schema(description = "返回数量限制")
	private Integer limit;

	String normalizedAction() {
		return StringUtils.defaultIfBlank(action, "SQL_VERIFY").trim().toUpperCase();
	}

}
