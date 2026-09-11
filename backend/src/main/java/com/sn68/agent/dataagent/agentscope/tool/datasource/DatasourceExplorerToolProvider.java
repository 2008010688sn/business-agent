/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool.datasource;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.ToolContextRequestResolver;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.tool.SkillResourceToolProvider;
import com.sn68.agent.dataagent.agentscope.tool.ToolError;
import com.sn68.agent.dataagent.agentscope.tool.ToolErrorCode;
import com.sn68.agent.dataagent.multimodal.SqlResultCompactor;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Datasource exploration is registered only from a routed Skill resource snapshot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasourceExplorerToolProvider implements SkillResourceToolProvider {

	private static final String TOOL_NAME = AgentModelToolName.DATASOURCE_SKILL_SEARCH;

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final List<String> SCHEMA_TABLE_KEYS = List.of("name");

	private static final List<String> SCHEMA_COLUMN_KEYS = List.of("name", "type");

	private static final List<String> SCHEMA_RELATION_KEYS = List.of("sourceTable", "sourceColumn", "targetTable",
			"targetColumn");

	private static final String INPUT_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "action": {"type": "string", "enum": ["FIND_TABLES", "GET_TABLE_SCHEMA", "GET_RELATED_TABLES", "PREVIEW_ROWS", "SEARCH"]},
			    "query": {"type": "string"},
			    "tableName": {"type": "string"},
			    "sql": {"type": "string"},
			    "limit": {"type": "integer"},
			    "detailLevel": {"type": "string", "enum": ["COMPACT"]}
			  },
			  "required": ["action"]
			}
			""";

	private static final String EMPTY_SEARCH_HINT =
			"本次查询结果为空：请检查过滤条件与时间口径，必要时调整 SQL 后重试；不要用完全相同的参数重复查询。";

	/**
	 * 数据库查询耗时提示的慢查询阈值（毫秒），与数据源查询分段日志、运行时慢工具 HINT 保持同一 5000ms 口径。
	 * 达到阈值时在 summary 末尾追加耗时说明，让模型能向用户如实解释长时间静默的原因；不新增任何配置键。
	 */
	private static final long SLOW_QUERY_HINT_MS = 5000L;

	private final DatasourceExplorerService datasourceExplorerService;

	private final ObjectMapper objectMapper;

	private final DatasourceRuntimeContextCache runtimeContextCache;

	private final SqlResultCompactor sqlResultCompactor;

	private final DataAgentProperties dataAgentProperties;

	@Override
	public Map<String, ToolCallback> getSkillToolCallbacks(SkillVersionResources resources) {
		if (resources == null || !resources.hasDatasourceAccess()) {
			return Map.of();
		}
		ToolDefinition definition = ToolDefinition.builder()
			.name(TOOL_NAME)
			.description(
					"Read-only datasource exploration limited to the routed Skill version snapshot. PostgreSQL boolean columns must use TRUE/FALSE, never 0/1. GET_TABLE_SCHEMA always returns COMPACT columns (name/type); do not request FULL. Call GET_TABLE_SCHEMA only when a join key or column type is missing.")
			.inputSchema(INPUT_SCHEMA)
			.build();
		return Map.of(TOOL_NAME,
				new SkillBoundDatasourceExplorerToolCallback(definition, datasourceExplorerService, objectMapper,
						runtimeContextCache, sqlResultCompactor, dataAgentProperties));
	}

	/**
	 * 把本次数据库查询耗时回填到结果契约字段 queryDurationMs，并在达到 {@link #SLOW_QUERY_HINT_MS} 时
	 * 向 summary 追加耗时说明。对所有 action 生效，需在压缩/空结果引导/schema 裁剪之前调用，
	 * 字段才能随各分支一并下发。包内可见以便单测覆盖慢查询分支。
	 */
	static void applyQueryDuration(DatasourceExplorerResult result, long queryDurationMs) {
		if (result == null) {
			return;
		}
		result.setQueryDurationMs(queryDurationMs);
		if (queryDurationMs < SLOW_QUERY_HINT_MS) {
			return;
		}
		String durationHint = "，本次数据库查询耗时 " + String.format(Locale.ROOT, "%.1f", queryDurationMs / 1000.0) + " s";
		result.setSummary(StringUtils.hasText(result.getSummary()) ? result.getSummary() + durationHint
				: durationHint.substring(1));
	}

	private static final class SkillBoundDatasourceExplorerToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition;

		private final DatasourceExplorerService datasourceExplorerService;

		private final ObjectMapper objectMapper;

		private final DatasourceRuntimeContextCache runtimeContextCache;

		private final SqlResultCompactor sqlResultCompactor;

		private final DataAgentProperties dataAgentProperties;

		private SkillBoundDatasourceExplorerToolCallback(ToolDefinition toolDefinition,
				DatasourceExplorerService datasourceExplorerService, ObjectMapper objectMapper,
				DatasourceRuntimeContextCache runtimeContextCache, SqlResultCompactor sqlResultCompactor,
				DataAgentProperties dataAgentProperties) {
			this.toolDefinition = toolDefinition;
			this.datasourceExplorerService = datasourceExplorerService;
			this.objectMapper = objectMapper;
			this.runtimeContextCache = runtimeContextCache;
			this.sqlResultCompactor = sqlResultCompactor;
			this.dataAgentProperties = dataAgentProperties;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return toolDefinition;
		}

		@Override
		public String call(String toolInput) {
			return call(toolInput, null);
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			try {
				DatasourceExplorerRequest request = objectMapper.readValue(toolInput, DatasourceExplorerRequest.class);
				validateRequest(request);
				AgentRequest agentRequest = ToolContextRequestResolver.resolveGraphRequest(toolContext);
				if (isSchemaProbe(request) && runtimeContextCache.hasSuccessfulSearch(agentRequest)) {
					return searchAlreadyDonePayload();
				}
				String metadataReadKey = metadataReadKey(request);
				int maxSchemaTables = resolveMaxSchemaTables();
				if (metadataReadKey != null
						&& !runtimeContextCache.tryAcquireMetadataRead(agentRequest, metadataReadKey, maxSchemaTables)) {
					return schemaBudgetExceededPayload(maxSchemaTables);
				}
				try {
					long queryStartNanos = System.nanoTime();
					DatasourceExplorerResult result = datasourceExplorerService.execute(request, agentRequest);
					long queryDurationMs = (System.nanoTime() - queryStartNanos) / 1_000_000L;
					if (request.getAction() == DatasourceExplorerAction.SEARCH && hasSearchRows(result)) {
						runtimeContextCache.markSearchSucceeded(agentRequest);
					}
					return objectMapper.writeValueAsString(responsePayload(request, result, queryDurationMs));
				}
				catch (Exception ex) {
					if (metadataReadKey != null) {
						runtimeContextCache.releaseMetadataRead(agentRequest, metadataReadKey);
					}
					throw ex;
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException(objectToJson(
						ToolError.of(ToolErrorCode.EXECUTION_FAILED, "Datasource exploration failed: " + ex.getMessage())), ex);
			}
		}

		private void validateRequest(DatasourceExplorerRequest request) {
			if (request == null || request.getAction() == null) {
				throw new IllegalArgumentException(objectToJson(ToolError.of(ToolErrorCode.INVALID_INPUT,
						"Datasource exploration requires action")));
			}
			switch (request.getAction()) {
				case FIND_TABLES -> requireText(request.getQuery(), "FIND_TABLES requires query");
				case GET_TABLE_SCHEMA, GET_RELATED_TABLES, PREVIEW_ROWS ->
					requireText(request.getTableName(), request.getAction().name() + " requires tableName");
				case SEARCH -> requireText(request.getSql(), "SEARCH requires sql");
				case LIST_TABLES -> throw new IllegalArgumentException(objectToJson(ToolError.of(
						ToolErrorCode.UNSUPPORTED_ACTION, "LIST_TABLES is not available to routed ReAct Skills")));
				default -> throw new IllegalArgumentException(objectToJson(ToolError.of(ToolErrorCode.UNSUPPORTED_ACTION,
						"Unsupported datasource action: " + request.getAction())));
			}
		}

		private int resolveMaxSchemaTables() {
			if (dataAgentProperties == null || dataAgentProperties.getRuntime() == null) {
				return DatasourceRuntimeContextCache.DEFAULT_MAX_SCHEMA_TABLES_PER_REQUEST;
			}
			int configured = dataAgentProperties.getRuntime().getMaxSchemaTablesPerRequest();
			return configured < 1 ? DatasourceRuntimeContextCache.DEFAULT_MAX_SCHEMA_TABLES_PER_REQUEST : configured;
		}

		private String schemaBudgetExceededPayload(int maxSchemaTables) {
			return objectToJson(Map.of("action", DatasourceExplorerAction.GET_TABLE_SCHEMA.name(), "searchReady", true,
					"summary", "本轮已加载 %d 张表结构，请基于已有结构直接 SEARCH，不要再探新表。".formatted(maxSchemaTables)));
		}

		private String searchAlreadyDonePayload() {
			return objectToJson(Map.of("action", DatasourceExplorerAction.SEARCH.name(), "searchReady", true,
					"summary", "已有查询结果，请直接作答，不要再探表。"));
		}

		private boolean isSchemaProbe(DatasourceExplorerRequest request) {
			return request.getAction() == DatasourceExplorerAction.GET_TABLE_SCHEMA
					|| request.getAction() == DatasourceExplorerAction.FIND_TABLES;
		}

		private boolean hasSearchRows(DatasourceExplorerResult result) {
			return result != null && result.getRows() != null && !result.getRows().isEmpty();
		}

		/**
		 * SEARCH 空结果引导：显式标记结果为空并给出纠偏方向，避免模型把空结果当异常，
		 * 或用完全相同的参数反复重试。
		 */
		private Map<String, Object> emptySearchPayload(DatasourceExplorerResult result) {
			Map<String, Object> payload = result == null ? new LinkedHashMap<>()
					: new LinkedHashMap<>(objectMapper.convertValue(result, MAP_TYPE));
			payload.put("empty", true);
			payload.put("hint", EMPTY_SEARCH_HINT);
			return payload;
		}

		private String metadataReadKey(DatasourceExplorerRequest request) {
			if (request.getAction() == DatasourceExplorerAction.GET_TABLE_SCHEMA) {
				return "GET_TABLE_SCHEMA:" + request.getTableName().trim().toLowerCase(Locale.ROOT);
			}
			return null;
		}

		private Object responsePayload(DatasourceExplorerRequest request, DatasourceExplorerResult result,
				long queryDurationMs) {
			DataAgentProperties.Multimodal multimodal = dataAgentProperties == null
					|| dataAgentProperties.getMultimodal() == null ? new DataAgentProperties.Multimodal()
							: dataAgentProperties.getMultimodal();
			if (request.getAction() == DatasourceExplorerAction.SEARCH) {
				result = sqlResultCompactor.compactForModel(result, multimodal.getSqlMaxCellChars());
			}
			else if (request.getAction() == DatasourceExplorerAction.PREVIEW_ROWS) {
				result = sqlResultCompactor.compact(result, multimodal.getSqlKeepAllRows(),
						multimodal.getSqlMaxCellChars());
			}
			applyQueryDuration(result, queryDurationMs);
			boolean schemaAction = request.getAction() == DatasourceExplorerAction.FIND_TABLES
					|| request.getAction() == DatasourceExplorerAction.GET_TABLE_SCHEMA
					|| request.getAction() == DatasourceExplorerAction.GET_RELATED_TABLES;
			if (!schemaAction) {
				if (request.getAction() == DatasourceExplorerAction.SEARCH && !hasSearchRows(result)) {
					return emptySearchPayload(result);
				}
				return result;
			}
			Map<String, Object> payload = objectMapper.convertValue(result, MAP_TYPE);
			if (request.getAction() != DatasourceExplorerAction.GET_TABLE_SCHEMA
					&& request.effectiveDetailLevel() == DatasourceExplorerDetailLevel.FULL) {
				stripSamplesFromColumns(payload, true);
				return payload;
			}
			compactMapList(payload, "tables", SCHEMA_TABLE_KEYS);
			compactMapList(payload, "columns", SCHEMA_COLUMN_KEYS);
			compactMapList(payload, "relations", SCHEMA_RELATION_KEYS);
			payload.remove("relationEvidence");
			return payload;
		}

		private void compactMapList(Map<String, Object> payload, String key, List<String> keepKeys) {
			Object value = payload.get(key);
			if (!(value instanceof Collection<?> values)) {
				return;
			}
			payload.put(key, values.stream()
				.filter(Map.class::isInstance)
				.map(Map.class::cast)
				.map(item -> pickKeys(item, keepKeys))
				.toList());
		}

		private Map<String, Object> pickKeys(Map<?, ?> source, List<String> keepKeys) {
			Map<String, Object> result = new LinkedHashMap<>();
			for (String keep : keepKeys) {
				Object value = source.get(keep);
				if ("type".equals(keep) && isEmptyCompactValue(value)) {
					result.put("type", "unknown");
					continue;
				}
				if (!isEmptyCompactValue(value)) {
					result.put(keep, value);
				}
			}
			return result;
		}

		private void stripSamplesFromColumns(Map<String, Object> payload, boolean full) {
			Object columns = payload.get("columns");
			if (!(columns instanceof Collection<?> values)) {
				return;
			}
			for (Object item : values) {
				if (item instanceof Map<?, ?> map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> column = (Map<String, Object>) map;
					stripUnsafeSamples(column, full);
				}
			}
		}

		private void stripUnsafeSamples(Map<String, Object> column, boolean full) {
			if (!column.containsKey("samples")) {
				return;
			}
			String name = String.valueOf(column.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
			if (looksLikeIdentifierColumn(name) || !full) {
				column.remove("samples");
				return;
			}
			if (!looksLikeEnumColumn(name)) {
				column.remove("samples");
			}
		}

		private boolean looksLikeIdentifierColumn(String name) {
			return name.equals("id") || name.endsWith("_id") || name.endsWith("id") || name.endsWith("_no")
					|| name.endsWith("_code") || name.contains("uuid");
		}

		private boolean looksLikeEnumColumn(String name) {
			return name.contains("status") || name.contains("type") || name.contains("state") || name.contains("flag")
					|| name.contains("状态") || name.contains("类型");
		}

		private boolean isEmptyCompactValue(Object value) {
			return value == null || Boolean.FALSE.equals(value)
					|| value instanceof String text && !StringUtils.hasText(text)
					|| value instanceof Collection<?> collection && collection.isEmpty()
					|| value instanceof Map<?, ?> map && map.isEmpty();
		}

		private void requireText(String value, String message) {
			if (!StringUtils.hasText(value)) {
				throw new IllegalArgumentException(objectToJson(ToolError.of(ToolErrorCode.INVALID_INPUT, message)));
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
				return "{\"code\":\"EXECUTION_FAILED\",\"message\":\"Tool error serialization failed\"}";
			}
		}
	}
}
