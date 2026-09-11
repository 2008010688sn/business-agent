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

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * 数据源探索工具执行结果。
 */
@Schema(description = "数据源探索工具执行结果")
@Data
@Builder
public class DatasourceExplorerResult {

	@Schema(description = "数据源标识")
	private String datasource;

	@Schema(description = "执行动作")
	private String action;

	@Schema(description = "结果摘要")
	private String summary;

	@Schema(description = "本次数据库查询总耗时（毫秒），由工具提供方计时并回填")
	private Long queryDurationMs;

	@Schema(description = "是否可用于检索")
	private Boolean searchReady;

	@Schema(description = "查询结果是否为空")
	private Boolean emptyResult;

	@Schema(description = "建议的下一步动作")
	private String suggestedNextAction;

	@Schema(description = "表清单")
	@Builder.Default
	private List<Map<String, Object>> tables = new ArrayList<>();

	@Schema(description = "字段清单")
	@Builder.Default
	private List<Map<String, Object>> columns = new ArrayList<>();

	@Schema(description = "数据行样例")
	@Builder.Default
	private List<Map<String, Object>> rows = new ArrayList<>();

	@Schema(description = "用户请求的结果行数")
	private Integer requestedRows;

	@Schema(description = "实际返回的结果行数")
	private Integer returnedRows;

	@Schema(description = "平台实际应用的结果行数上限")
	private Integer appliedLimit;

	@Schema(description = "平台上限之后是否确认仍有数据")
	private Boolean hasMore;

	@Schema(description = "结果覆盖状态")
	private ResultCoverageStatus coverageStatus;

	@Schema(description = "关系清单")
	@Builder.Default
	private List<Map<String, Object>> relations = new ArrayList<>();

	@Schema(description = "执行 SQL")
	private String sql;

	@Schema(description = "已使用表名")
	@Builder.Default
	private List<String> usedTables = new ArrayList<>();

	@Schema(description = "已使用字段名")
	@Builder.Default
	private List<String> usedColumns = new ArrayList<>();

	@Schema(description = "关系证据")
	@Builder.Default
	private List<Map<String, Object>> relationEvidence = new ArrayList<>();

	@Schema(description = "工具决策原因")
	@Builder.Default
	private List<String> toolDecisionReasons = new ArrayList<>();

	@Schema(description = "结果范围明细")
	@Builder.Default
	private List<String> resultScopeDetails = new ArrayList<>();

	@Schema(description = "结果范围")
	private String resultScope;

	@Schema(description = "决策原因")
	private String decisionReason;

}
