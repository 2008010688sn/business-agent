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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * SQL 守护工具返回结构：校验决策（safe_to_execute/revise_sql）、问题清单、修复建议与逐规则结论，
 * 序列化时省略空字段以压缩工具返回体积。
 */
@Schema(description = "SQL 守护校验结果")
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
class SqlGuardCheckResult {

	@Schema(description = "校验决策")
	private String decision;

	@Schema(description = "目标表名")
	private String tableName;

	@Schema(description = "校验摘要")
	private String summary;

	@Schema(description = "稳定错误码；校验通过时为空")
	private String errorCode;

	@Schema(description = "已通过解析的候选 SQL")
	private String normalizedSql;

	@Schema(description = "是否匹配业务语义")
	@JsonProperty("isAligned")
	private Boolean isAligned;

	@Schema(description = "总行数")
	private Long totalRows;

	@Schema(description = "问题清单")
	@Builder.Default
	private List<SqlGuardProblem> problems = new ArrayList<>();

	@Schema(description = "修复建议")
	@Builder.Default
	private List<String> fixSuggestions = new ArrayList<>();

	@Schema(description = "规则检查结果")
	@Builder.Default
	private List<SqlGuardRuleCheck> ruleChecks = new ArrayList<>();

	@Schema(description = "字段画像")
	@Builder.Default
	private List<Map<String, Object>> columnProfiles = new ArrayList<>();

}
