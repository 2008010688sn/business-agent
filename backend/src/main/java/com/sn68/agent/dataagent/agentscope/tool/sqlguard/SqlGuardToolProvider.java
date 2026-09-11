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
package com.sn68.agent.dataagent.agentscope.tool.sqlguard;

import lombok.extern.slf4j.Slf4j;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.ToolContextRequestResolver;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.tool.SkillResourceToolProvider;
import com.sn68.agent.dataagent.agentscope.tool.ToolError;
import com.sn68.agent.dataagent.agentscope.tool.ToolErrorCode;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * SQL 守护工具的注册入口：声明工具名、输入 Schema 与使用说明，把入参路由到 SqlVerifyExplainService
 * 完成意图一致性校验或数据画像，异常时返回结构化 ToolError。
 */
@Slf4j
@Component
public class SqlGuardToolProvider implements SkillResourceToolProvider {

	private static final String TOOL_NAME = AgentModelToolName.SQL_GUARD_CHECK;

	private static final String INPUT_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "action": {
			      "type": "string",
			      "enum": ["SQL_VERIFY", "DATA_PROFILE"],
			      "description": "可选。默认 SQL_VERIFY。"
			    },
			    "query": {
			      "type": "string",
			      "description": "SQL_VERIFY 时由运行时注入用户原始问题；无运行时上下文的直接调用需传入。"
			    },
			    "sql": {
			      "type": "string",
			      "description": "SQL_VERIFY 时必填。待校验 SQL。"
			    },
			    "tableName": {
			      "type": "string",
			      "description": "DATA_PROFILE 时必填。目标表名。"
			    },
			    "columnNames": {
			      "type": "array",
			      "items": {
			        "type": "string"
			      },
			      "description": "DATA_PROFILE 时可选。优先只传少量关键字段。"
			    },
			    "limit": {
			      "type": "integer",
			      "description": "DATA_PROFILE 时可选。返回上限，默认 5，最大 20。"
			    }
			  }
			}
			""";

	private static final String DESCRIPTION = """
			统一 SQL 守卫工具，供所有基于 SQL 的回答使用。
			1. `action=SQL_VERIFY`：只用于最终候选 SQL；在执行 SQL 或基于 SQL 生成最终回答前，检查它是否真正符合用户意图。
			2. `action=DATA_PROFILE`：字段枚举、过滤值分布和零结果诊断统一使用本动作；只有这些不确定性会实质影响过滤、分组、排序、时间窗口或指标写法时才调用。
			3. 不要把 DATA_PROFILE 当作每次查询的默认前置步骤；如果用户问题、schema 和列名已经足够明确，就直接跳过。
			4. 使用 DATA_PROFILE 时，优先传少量关键 `columnNames`，不要对整张表做无差别 profile。
			5. 候选 SQL 禁止使用 SELECT * 或 alias.*；在调用 SQL_VERIFY 前就应显式列出回答所需字段，避免因星号字段被拦截后再重写。
			6. 如果 SQL_VERIFY 返回 `isAligned=false`，请读取 `problems`、`ruleChecks` 和 `fixSuggestions`，自行改写 SQL 后再次调用 `sql_guard_check`。
			7. 如果使用 DATA_PROFILE，请重点读取返回的 `columnProfiles`，理解空值率、去重计数、高频值、样例值，以及字段更像枚举、数值还是时间字段。
			""";

	private final ObjectMapper objectMapper;

	private final SqlVerifyExplainService sqlVerifyExplainService;

	public SqlGuardToolProvider(ObjectMapper objectMapper, SqlVerifyExplainService sqlVerifyExplainService) {
		this.objectMapper = objectMapper;
		this.sqlVerifyExplainService = sqlVerifyExplainService;
	}

	@Override
	public Map<String, ToolCallback> getSkillToolCallbacks(SkillVersionResources resources) {
		if (resources == null || !resources.hasDatasourceAccess()) {
			return Map.of();
		}
		ToolDefinition toolDefinition = ToolDefinition.builder()
			.name(TOOL_NAME)
			.description(DESCRIPTION)
			.inputSchema(INPUT_SCHEMA)
			.build();
		return Map.of(TOOL_NAME,
				new SqlGuardToolCallback(toolDefinition, objectMapper, sqlVerifyExplainService));
	}

	private static final class SqlGuardToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition;

		private final ObjectMapper objectMapper;

		private final SqlVerifyExplainService sqlVerifyExplainService;

		private SqlGuardToolCallback(ToolDefinition toolDefinition, ObjectMapper objectMapper,
				SqlVerifyExplainService sqlVerifyExplainService) {
			this.toolDefinition = toolDefinition;
			this.objectMapper = objectMapper;
			this.sqlVerifyExplainService = sqlVerifyExplainService;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return toolDefinition;
		}

		@Override
		public String call(String toolInput) {
			return execute(toolInput, null);
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			return execute(toolInput, toolContext);
		}

		private String execute(String toolInput, ToolContext toolContext) {
			try {
				SqlGuardCheckRequest request = StringUtils.hasText(toolInput)
						? objectMapper.readValue(toolInput, SqlGuardCheckRequest.class) : new SqlGuardCheckRequest();
				enrichRequestFromToolContext(request, toolContext);
				String action = request.normalizedAction();
				validateRequest(request, action);
				SqlGuardCheckResult result = switch (action) {
					case "DATA_PROFILE" -> sqlVerifyExplainService
						.inspectProfile(ToolContextRequestResolver.resolveGraphRequest(toolContext), request);
					case "SQL_VERIFY" -> sqlVerifyExplainService.explain(request);
					default -> throw new IllegalArgumentException(objectToJson(
							ToolError.of(ToolErrorCode.UNSUPPORTED_ACTION, "不支持的 SQL 校验工具动作：" + action)));
				};
				return objectMapper.writeValueAsString(toModelView(action, result));
			}
			catch (Exception ex) {
				throw new IllegalStateException(objectToJson(
						ToolError.of(ToolErrorCode.EXECUTION_FAILED, "SQL 校验工具执行失败：" + ex.getMessage())), ex);
			}
		}

		private Object toModelView(String action, SqlGuardCheckResult result) {
			if ("DATA_PROFILE".equals(action)) {
				return dataProfileView(result);
			}
			if (!"SQL_VERIFY".equals(action) || !Boolean.TRUE.equals(result.getIsAligned())) {
				return result;
			}
			Map<String, Object> view = new LinkedHashMap<>();
			view.put("decision", result.getDecision());
			view.put("isAligned", true);
			view.put("summary", result.getSummary());
			view.put("normalizedSql", result.getNormalizedSql());
			return view;
		}

		private Map<String, Object> dataProfileView(SqlGuardCheckResult result) {
			Map<String, Object> view = new LinkedHashMap<>();
			if (result == null) {
				return view;
			}
			view.put("decision", result.getDecision());
			view.put("summary", result.getSummary());
			view.put("tableName", result.getTableName());
			if (result.getTotalRows() != null) {
				view.put("totalRows", result.getTotalRows());
			}
			view.put("columns", slimProfileColumns(result.getColumnProfiles()));
			return view;
		}

		private List<Map<String, Object>> slimProfileColumns(List<Map<String, Object>> profiles) {
			if (profiles == null || profiles.isEmpty()) {
				return List.of();
			}
			List<Map<String, Object>> columns = new ArrayList<>();
			for (Map<String, Object> profile : profiles) {
				if (profile == null) {
					continue;
				}
				Map<String, Object> column = new LinkedHashMap<>();
				Object name = profile.get("columnName");
				if (name == null) {
					name = profile.get("name");
				}
				Object type = profile.get("dataType");
				if (type == null) {
					type = profile.get("type");
				}
				if (name != null && StringUtils.hasText(String.valueOf(name))) {
					column.put("name", name);
				}
				if (type != null && StringUtils.hasText(String.valueOf(type))) {
					column.put("type", type);
				}
				else if (!column.isEmpty()) {
					column.put("type", "unknown");
				}
				if (!column.isEmpty()) {
					columns.add(column);
				}
			}
			return columns;
		}

		private void validateRequest(SqlGuardCheckRequest request, String action) {
			if ("DATA_PROFILE".equals(action)) {
				requireText(request.getTableName(), "DATA_PROFILE 需要 tableName 参数");
				return;
			}
			if ("SQL_VERIFY".equals(action)) {
				requireText(request.getQuery(), "SQL_VERIFY 需要 query 参数");
				requireText(request.getSql(), "SQL_VERIFY 需要 sql 参数");
			}
		}

		private void requireText(String value, String message) {
			if (!StringUtils.hasText(value)) {
				throw new IllegalArgumentException(objectToJson(ToolError.of(ToolErrorCode.INVALID_INPUT, message)));
			}
		}

		private void enrichRequestFromToolContext(SqlGuardCheckRequest request, ToolContext toolContext) {
			if (request == null) {
				return;
			}
			AgentRequest agentRequest = ToolContextRequestResolver.resolveGraphRequest(toolContext);
			if (agentRequest == null) {
				return;
			}
			request.setQuery(agentRequest.getQuery());
			if (!StringUtils.hasText(request.getHumanFeedbackContent())) {
				request.setHumanFeedbackContent(agentRequest.getHumanFeedbackContent());
			}
		}

		private String objectToJson(Object value) {
			try {
				return objectMapper.writeValueAsString(value);
			}
			catch (Exception ex) {
				// The real cause never reaches the model or the logs otherwise; only this generic frame does.
				log.warn("Failed to serialize the tool error payload, returning a generic failure frame. valueType={}",
						value == null ? null : value.getClass().getName(), ex);
				return "{\"code\":\"EXECUTION_FAILED\",\"message\":\"工具错误序列化失败\"}";
			}
		}

	}

}
