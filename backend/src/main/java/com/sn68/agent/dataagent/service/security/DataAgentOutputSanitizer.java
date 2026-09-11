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
package com.sn68.agent.dataagent.service.security;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeToolDisplayNameResolver;
import com.sn68.agent.dataagent.agentscope.runtime.AgentUiResponseSupport;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.SemanticHitView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolStepView;
import com.sn68.agent.dataagent.service.report.SearchResultColumnNamer;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 按诊断权限清理模型输出中的思考块、来源信息和调试字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAgentOutputSanitizer {

	private static final String CONTENT_FORMAT_MARKDOWN = "markdown";

	private static final String SUGGESTED_REPLIES_KEY = "suggestedReplies";

	private static final String ANALYSIS_FOLLOW_UPS_KEY = "analysisFollowUps";

	private static final String SUGGESTED_REPLIES_SCHEMA_VERSION = "suggested-replies/v1";

	private static final Set<String> SUGGESTED_REPLIES_SOURCES = Set.of("query-clarify", "agent-runtime");

	private static final List<String> INTERACTION_WRAPPER_KEYS = List.of("businessClarification", "confirmation");

	private static final int MAX_SUGGESTED_REPLY_GROUPS = 4;

	private static final int MAX_SUGGESTED_REPLY_OPTIONS = 20;

	private static final int MAX_SUGGESTED_REPLY_PAYLOAD_KEYS = 20;

	private static final int MAX_SUGGESTED_REPLY_PAYLOAD_DEPTH = 5;

	private static final int MAX_SUGGESTED_REPLY_TEXT_LENGTH = 300;

	private static final Set<String> SUGGESTED_REPLY_DISPLAY_MODES = Set.of("buttons", "table", "narrow_required",
			"auto_fill");

	private static final Pattern SQL_CODE_BLOCK = Pattern.compile("(?is)```\\s*sql\\s+.*?```");

	private static final Pattern JSON_CODE_BLOCK = Pattern.compile("(?is)```\\s*json\\s+.*?```");

	private static final Pattern ECHARTS_CODE_BLOCK = Pattern.compile("(?is)`{3,}\\s*echarts\\s+.*?`{3,}");

	private static final Pattern SQL_STATEMENT = Pattern.compile(
			"(?is)\\b(select|with|insert|update|delete)\\b[\\s\\S]{0,320}\\b(from|join|where|set|values|group\\s+by|order\\s+by)\\b[\\s\\S]{0,320}");

	private static final Pattern PHYSICAL_IDENTIFIER = Pattern.compile(
			"`[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+`|(?<![\\u4e00-\\u9fa5A-Za-z0-9])[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+(?=\\s*表)");

	private static final List<String> FORBIDDEN_TERMS = List.of("SQL", "select", "from", "where", "join",
			"group by", "order by", "schema", "database", "datasource", "tool:", "semantic_model_search",
			"domain_business_knowledge_search", "datasource_skill_search", "sql_guard_check");

	private static final Map<String, String> KNOWN_DISPLAY_NAMES = Map.ofEntries(Map.entry("product_name", "产品名称"),
			Map.entry("product_no", "产品编号"), Map.entry("product_count", "产品数量"), Map.entry("company_name", "客户名称"),
			Map.entry("customer_name", "客户名称"), Map.entry("name", "客户名称"), Map.entry("metric", "指标"),
			Map.entry("value", "金额"), Map.entry("amount", "金额"), Map.entry("amt", "金额"),
			Map.entry("project_name", "项目名称"), Map.entry("project_no", "项目编号"), Map.entry("project_code", "项目编码"),
			Map.entry("one_project_name", "一级项目"),
			Map.entry("two_project_name", "二级项目"),
			Map.entry("project_count", "项目数"), Map.entry("bill_num", "账单数"), Map.entry("bill_no", "账单编号"),
			Map.entry("cn", "客户名称"), Map.entry("ct", "账单类型"), Map.entry("total", "合计"),
			Map.entry("total_amount", "总金额"), Map.entry("sum_amount", "总金额"), Map.entry("bill_amount", "账单金额"),
			Map.entry("fare", "运费"), Map.entry("total_fare", "总运费"), Map.entry("return_fare", "回箱运费"),
			Map.entry("total_return_fare", "回箱运费"), Map.entry("consumable_fare", "耗材费"),
			Map.entry("total_consumable_fare", "耗材费"), Map.entry("num", "数量"),
			Map.entry("quantity", "数量"), Map.entry("qty", "数量"), Map.entry("total_quantity", "总数量"),
			Map.entry("cnt", "数量"), Map.entry("count", "数量"), Map.entry("total_count", "总数"),
			Map.entry("bill_count", "账单数"), Map.entry("order_count", "订单数"), Map.entry("demand_count", "需求数"),
			Map.entry("price", "单价"), Map.entry("total_price", "租金/商品价"), Map.entry("settlement_date", "结算周期"),
			Map.entry("settlement_cycle", "结算周期"), Map.entry("create_time", "生成时间"), Map.entry("status", "状态"),
			Map.entry("contract_type", "账单类型"), Map.entry("settlement_node", "结算节点"),
			Map.entry("from_site_name", "发货网点"), Map.entry("to_site_name", "收货网点"),
			Map.entry("dispatch_num", "调度数"), Map.entry("dispatch_count", "调度数"), Map.entry("out_num", "出库数"),
			Map.entry("out_count", "出库数"), Map.entry("send_num", "发箱数"), Map.entry("send_count", "发箱数"),
			Map.entry("sign_num", "签收数"), Map.entry("sign_count", "签收数"), Map.entry("receive_num", "收箱数"),
			Map.entry("receive_count", "收箱数"), Map.entry("receiver_num", "收箱数"),
			Map.entry("receiver_count", "收箱数"), Map.entry("service_fee", "服务费"),
			Map.entry("total_service_fee", "服务费"), Map.entry("maintenance_fee", "维修基金"),
			Map.entry("total_maintenance_fee", "维修基金"), Map.entry("adjust_amount", "调整金额"),
			Map.entry("subsist", "预付款"), Map.entry("security_deposit_rate", "质保金比例"), Map.entry("bail", "押金"));

	private static final String BUSINESS_FAILED = "BUSINESS_FAILED";

	/**
	 * 思考快照中需要替换为业务展示名的内部工具标识；原文仅诊断权限用户可见，普通用户面只留业务名。
	 */
	private static final List<String> INTERNAL_TOOL_NAMES = List.of("datasource_skill_search",
			"domain_business_knowledge_search", "semantic_model_search", "sql_guard_check", "query_clarify.check");

	private static final String GENERIC_TOOL_DISPLAY_NAME = "执行工具操作";

	private static final String INTERNAL_TOOL_FALLBACK_NAME = "内部工具";

	private final ObjectMapper objectMapper;

	/**
	 * 处理DataAgentOutputSanitizer。
	 */
	public String sanitizeText(String text, AnswerTraceExplainView explain) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		ReplacementPlan plan = buildReplacementPlan(explain);
		// 模型不该把不可信数据边界标记写进正文，真写了也不能让用户看到一个看似可用的标记。
		String sanitized = UntrustedContentBoundary.stripMarkers(text);
		sanitized = SQL_CODE_BLOCK.matcher(sanitized).replaceAll("");
		sanitized = JSON_CODE_BLOCK.matcher(sanitized).replaceAll("");
		sanitized = SQL_STATEMENT.matcher(sanitized).replaceAll("（内部查询细节已隐藏）");
		for (Map.Entry<String, String> entry : plan.replacements().entrySet()) {
			sanitized = replaceToken(sanitized, entry.getKey(), entry.getValue());
		}
		for (Map.Entry<String, String> entry : knownPhysicalNameReplacements().entrySet()) {
			sanitized = replaceToken(sanitized, entry.getKey(), entry.getValue());
		}
		for (String term : FORBIDDEN_TERMS) {
			sanitized = replaceToken(sanitized, term, "内部处理");
		}
		sanitized = PHYSICAL_IDENTIFIER.matcher(sanitized).replaceAll("业务对象");
		return sanitized;
	}

	/**
	 * 思考快照专用脱敏：与答案级 {@link #sanitizeText} 保持代码块剥离、SQL 语句隐藏和表/字段物理标识替换语义，
	 * 但不做 FORBIDDEN_TERMS 全量替换（避免把正文里的普通词也换成"内部处理"），
	 * 并额外把已知内部工具标识替换为业务展示名。工具原始结果在思考快照中只允许保留摘要计数，原文仅诊断权限可见。
	 * 返回纯文本，调用方负责先脱敏后转义。
	 */
	public String redactForThinking(String text, AnswerTraceExplainView explain) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		ReplacementPlan plan = buildReplacementPlan(explain);
		// 模型不该把不可信数据边界标记写进正文，真写了也不能让用户看到一个看似可用的标记。
		String sanitized = UntrustedContentBoundary.stripMarkers(text);
		sanitized = SQL_CODE_BLOCK.matcher(sanitized).replaceAll("");
		sanitized = JSON_CODE_BLOCK.matcher(sanitized).replaceAll("");
		sanitized = SQL_STATEMENT.matcher(sanitized).replaceAll("（内部查询细节已隐藏）");
		for (Map.Entry<String, String> entry : plan.replacements().entrySet()) {
			sanitized = replaceToken(sanitized, entry.getKey(), entry.getValue());
		}
		for (Map.Entry<String, String> entry : knownPhysicalNameReplacements().entrySet()) {
			sanitized = replaceToken(sanitized, entry.getKey(), entry.getValue());
		}
		for (String toolName : INTERNAL_TOOL_NAMES) {
			sanitized = replaceToken(sanitized, toolName, toolDisplayNameForThinking(toolName));
		}
		sanitized = PHYSICAL_IDENTIFIER.matcher(sanitized).replaceAll("业务对象");
		return sanitized;
	}

	/**
	 * 解析思考快照中的工具展示名；解析不到业务名时用"内部工具"兜底，避免内部工具标识外露。
	 */
	public String toolDisplayNameForThinking(String toolName) {
		String displayName = AgentRuntimeToolDisplayNameResolver.displayName(toolName);
		if (!StringUtils.hasText(displayName) || GENERIC_TOOL_DISPLAY_NAME.equals(displayName)) {
			return INTERNAL_TOOL_FALLBACK_NAME;
		}
		return displayName;
	}

	/**
	 * 消毒 Markdown 正文，但原样保留已由后端生成的 echarts 围栏，避免分类名命中 from/join 等词后 JSON 被破坏。
	 */
	public String sanitizeTextPreservingEcharts(String text, AnswerTraceExplainView explain) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		Matcher matcher = ECHARTS_CODE_BLOCK.matcher(text);
		StringBuilder builder = new StringBuilder();
		int last = 0;
		while (matcher.find()) {
			builder.append(sanitizeText(text.substring(last, matcher.start()), explain));
			builder.append(matcher.group());
			last = matcher.end();
		}
		builder.append(sanitizeText(text.substring(last), explain));
		return builder.toString();
	}

	/**
	 * 处理DataAgentOutputSanitizer。
	 */
	public String sanitizeResultSetJson(String json, AnswerTraceExplainView explain) {
		if (!StringUtils.hasText(json)) {
			return "";
		}
		try {
			JsonNode root = objectMapper.readTree(json);
			if (!root.isObject()) {
				return sanitizeText(json, explain);
			}
			ObjectNode copy = root.deepCopy();
			ObjectNode resultSet = copy.path("resultSet").isObject() ? (ObjectNode) copy.path("resultSet") : copy;
			sanitizeColumnsAndRows(resultSet, explain);
			return objectMapper.writeValueAsString(copy);
		}
		catch (Exception ex) {
			// Falling back to text sanitization is weaker than the structured path, so the downgrade must be visible.
			log.warn("Failed to sanitize the structured result set, falling back to text sanitization. length={}",
					json == null ? 0 : json.length(), ex);
			return sanitizeText(json, explain);
		}
	}

	/**
	 * 处理DataAgentOutputSanitizer。
	 */
	public AgentResponse sanitizeAgentResponse(AgentResponse response, AnswerTraceExplainView explain) {
		if (response == null || response.isComplete()) {
			return response;
		}
		String text = response.getText();
		if (response.getTextType() == TextType.RESULT_SET) {
			text = sanitizeResultSetJson(text, explain);
		}
		else if (response.getTextType() == TextType.TEXT || response.getTextType() == TextType.MARK_DOWN
				|| response.getTextType() == TextType.JSON || response.getTextType() == TextType.SQL) {
			text = sanitizeText(text, explain);
		}
		return AgentResponse.builder()
			.agentId(response.getAgentId())
			.threadId(response.getThreadId())
			.nodeName(response.getNodeName())
			.textType(response.getTextType())
			.text(text)
			.metadata(sanitizeMetadata(response.getMetadata(), explain))
			.error(response.isError())
			.complete(response.isComplete())
			.build();
	}

	/**
	 * 处理DataAgentOutputSanitizer。
	 */
	public Map<String, Object> sanitizeMetadata(Map<String, Object> metadata, AnswerTraceExplainView explain) {
		if (metadata == null || metadata.isEmpty()) {
			return metadata;
		}
		if (AgentUiResponseSupport.isStructuredUiMetadata(metadata)) {
			return sanitizeAgentUiMetadata(metadata, explain);
		}
		Map<String, Object> sanitized = new LinkedHashMap<>();
		if (metadata.get("longTermMemory") instanceof Map<?, ?> memory) {
			Map<String, Object> safeMemory = new LinkedHashMap<>();
			copyIfPresent(memory, safeMemory, "referencedCount");
			copyIfPresent(memory, safeMemory, "estimatedTokens");
			Object hits = memory.get("hits");
			if (hits instanceof List<?> hitList) {
				List<Map<String, Object>> safeHits = new ArrayList<>();
				for (Object item : hitList) {
					if (!(item instanceof Map<?, ?> hit)) {
						continue;
					}
					Map<String, Object> safeHit = new LinkedHashMap<>();
					copyIfPresent(hit, safeHit, "type");
					copyIfPresent(hit, safeHit, "sourceTime");
					copyIfPresent(hit, safeHit, "similarity");
					copyIfPresent(hit, safeHit, "injected");
					Object summary = hit.get("summary");
					if (summary != null) {
						safeHit.put("summary", sanitizeText(String.valueOf(summary), explain));
					}
					safeHits.add(safeHit);
				}
				safeMemory.put("hits", safeHits);
			}
			sanitized.put("longTermMemory", safeMemory);
		}
		if (CONTENT_FORMAT_MARKDOWN.equals(metadata.get("contentFormat"))) {
			sanitized.put("contentFormat", CONTENT_FORMAT_MARKDOWN);
		}
		if (metadata.get("runtimeRequestId") instanceof String runtimeRequestId && StringUtils.hasText(runtimeRequestId)) {
			sanitized.put("runtimeRequestId", runtimeRequestId.trim());
		}
		copyAnalysisFollowUps(metadata, sanitized, explain);
		int interactionWrapperCount = copyInteractionMetadata(metadata, sanitized, explain);
		if (interactionWrapperCount == 0) {
			copyClarifyMetadata(metadata, sanitized, explain);
			copySuggestedReplies(metadata, sanitized, explain);
		}
		return sanitized.isEmpty() ? null : sanitized;
	}

	private Map<String, Object> sanitizeAgentUiMetadata(Map<String, Object> metadata, AnswerTraceExplainView explain) {
		if (isSkillFlowMetadata(metadata)) {
			return sanitizeSkillFlowMetadata(metadata, explain);
		}
		Map<String, Object> sanitized = new LinkedHashMap<>();
		metadata.forEach((key, value) -> {
			if (SUGGESTED_REPLIES_KEY.equals(key)) {
				Object safeSuggestedReplies = sanitizeSuggestedReplies(value, explain);
				if (safeSuggestedReplies != null) {
					sanitized.put(key, safeSuggestedReplies);
				}
				return;
			}
			sanitized.put(key, sanitizeAgentUiMetadataValue(value, explain));
		});
		return sanitized;
	}

	private boolean isSkillFlowMetadata(Map<String, Object> metadata) {
		if ("skill-flow".equals(metadata.get("messageType"))) {
			return true;
		}
		Object agentUi = metadata.get("agentUi");
		return agentUi instanceof AgentUiMessage message && "skill-flow".equals(message.kind())
				|| agentUi instanceof Map<?, ?> map && "skill-flow".equals(map.get("kind"));
	}

	private Map<String, Object> sanitizeSkillFlowMetadata(Map<String, Object> metadata,
			AnswerTraceExplainView explain) {
		Map<String, Object> sanitized = new LinkedHashMap<>();
		metadata.forEach((key, value) -> {
			if ("agentUi".equals(key)) {
				sanitized.put(key, sanitizeSkillFlowValue(value, explain, "agentUi"));
				return;
			}
			if ("uiSchemaVersion".equals(key) || "messageType".equals(key)) {
				sanitized.put(key, value);
			}
		});
		return sanitized;
	}

	@SuppressWarnings("unchecked")
	private Object sanitizeSkillFlowValue(Object value, AnswerTraceExplainView explain, String fieldName) {
		if (value instanceof AgentUiMessage message) {
			Map<String, Object> converted = objectMapper.convertValue(message,
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
					});
			return sanitizeSkillFlowValue(converted, explain, fieldName);
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> sanitized = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String key = String.valueOf(entry.getKey());
				Object nestedValue = entry.getValue();
				if (!"source".equals(fieldName) && ("rawData".equals(key)
						|| isBusinessIdKey(key) && !isFlowControlId(key))) {
					continue;
				}
				if ("source".equals(fieldName)) {
					if ("flowInstanceId".equals(key)) {
						sanitized.put(key, sanitizeSkillFlowControlValue(nestedValue));
					}
				}
				else if ("content".equals(fieldName) && "text".equals(key)) {
					sanitized.put(key, sanitizeFlowText(nestedValue, explain));
				}
				else if ("actions".equals(fieldName) && "label".equals(key)) {
					sanitized.put(key, sanitizeFlowText(nestedValue, explain));
				}
				else if ("options".equals(fieldName) && ("label".equals(key) || "summary".equals(key))) {
					sanitized.put(key, sanitizeFlowText(nestedValue, explain));
				}
				else if ("actions".equals(fieldName)) {
					if ("value".equals(key) && "SELECT".equals(String.valueOf(map.get("type")))) {
						continue;
					}
					sanitized.put(key, sanitizeSkillFlowActionValue(key, nestedValue, explain));
				}
				else if ("options".equals(fieldName)) {
					sanitized.put(key, sanitizeSkillFlowOptionValue(key, nestedValue, explain));
				}
				else if ("steps".equals(fieldName)) {
					// 步骤条目只有展示名需要文本清洗；kind/status/durationMs 是控制值原样透传，
					// 且不进通用递归，避免命中业务 ID 剥离逻辑。
					sanitized.put(key, "label".equals(key) ? sanitizeFlowText(nestedValue, explain)
							: sanitizeSkillFlowControlValue(nestedValue));
				}
				else {
					sanitized.put(key, sanitizeSkillFlowValue(nestedValue, explain, key));
				}
			}
			return sanitized;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(item -> sanitizeSkillFlowValue(item, explain, fieldName)).toList();
		}
		return value;
	}

	private Object sanitizeSkillFlowActionValue(String key, Object value, AnswerTraceExplainView explain) {
		if ("label".equals(key)) {
			return sanitizeFlowText(value, explain);
		}
		if ("payload".equals(key)) {
			return sanitizeSkillFlowValue(value, explain, "actionPayload");
		}
		return sanitizeSkillFlowControlValue(value);
	}

	private Object sanitizeSkillFlowOptionValue(String key, Object value, AnswerTraceExplainView explain) {
		if ("label".equals(key) || "summary".equals(key)) {
			return sanitizeFlowText(value, explain);
		}
		return sanitizeSkillFlowControlValue(value);
	}

	private boolean isFlowControlId(String key) {
		return Set.of("runtimeRequestId", "agentId", "flowInstanceId", "nodeId", "actionId", "optionId",
				"selectionId", "selectionToken", "groupId").contains(key);
	}

	private Object sanitizeSkillFlowControlValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> copy = new LinkedHashMap<>();
			map.forEach((key, nestedValue) -> copy.put(String.valueOf(key), sanitizeSkillFlowControlValue(nestedValue)));
			return copy;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(this::sanitizeSkillFlowControlValue).toList();
		}
		return value;
	}

	private String sanitizeFlowText(Object value, AnswerTraceExplainView explain) {
		return value == null ? "" : truncateText(sanitizeText(String.valueOf(value), explain), MAX_SUGGESTED_REPLY_TEXT_LENGTH);
	}

	@SuppressWarnings("unchecked")
	private Object sanitizeAgentUiMetadataValue(Object value, AnswerTraceExplainView explain) {
		if (value instanceof AgentUiMessage message) {
			Map<String, Object> converted = objectMapper.convertValue(message,
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
					});
			return sanitizeAgentUiMetadataValue(converted, explain);
		}
		if (value instanceof String text) {
			return sanitizeText(text, explain);
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> sanitized = new LinkedHashMap<>();
			map.forEach((key, nestedValue) -> {
				String textKey = String.valueOf(key);
				if (!isInternalSuggestedReplyPayloadKey(textKey)) {
					sanitized.put(textKey, sanitizeAgentUiMetadataValue(nestedValue, explain));
				}
			});
			return sanitized;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(item -> sanitizeAgentUiMetadataValue(item, explain)).toList();
		}
		return value;
	}

	private void sanitizeColumnsAndRows(ObjectNode resultSet, AnswerTraceExplainView explain) {
		JsonNode columnsNode = resultSet.get("column");
		if (columnsNode == null) {
			columnsNode = resultSet.get("columns");
		}
		List<String> originalColumns = extractColumnNames(columnsNode);
		if (originalColumns.isEmpty() && resultSet.path("rows").isArray() && resultSet.path("rows").size() > 0) {
			resultSet.path("rows").get(0).fieldNames().forEachRemaining(originalColumns::add);
		}
		if (originalColumns.isEmpty() && resultSet.path("data").isArray() && resultSet.path("data").size() > 0) {
			resultSet.path("data").get(0).fieldNames().forEachRemaining(originalColumns::add);
		}
		if (originalColumns.isEmpty()) {
			return;
		}
		Map<String, String> resolvedNames = buildColumnDisplayNames(originalColumns, explain);
		Map<String, String> displayNames = new LinkedHashMap<>();
		Set<String> usedDisplayNames = new LinkedHashSet<>();
		for (String column : originalColumns) {
			displayNames.put(column, uniqueDisplayName(
					SearchResultColumnNamer.enforceUserFacing(userFacingColumnName(column, resolvedNames)),
					usedDisplayNames));
		}
		ArrayNode safeColumn = objectMapper.createArrayNode();
		for (String column : originalColumns) {
			safeColumn.add(displayNames.get(column));
		}
		if (resultSet.has("column")) {
			resultSet.set("column", safeColumn);
		}
		if (resultSet.has("columns")) {
			resultSet.set("columns", toSafeColumns(resultSet.get("columns"), originalColumns, displayNames));
		}
		renameRows(resultSet, "data", originalColumns, displayNames);
		renameRows(resultSet, "rows", originalColumns, displayNames);
		resultSet.remove(List.of("sql", "datasource", "usedTables", "usedColumns", "relationEvidence"));
	}

	private ArrayNode toSafeColumns(JsonNode columnsNode, List<String> originalColumns, Map<String, String> displayNames) {
		ArrayNode safeColumns = objectMapper.createArrayNode();
		if (columnsNode != null && columnsNode.isArray() && columnsNode.size() > 0 && columnsNode.get(0).isObject()) {
			for (JsonNode columnNode : columnsNode) {
				ObjectNode safeColumn = columnNode.deepCopy();
				String originalName = columnNode.path("name").asText("");
				safeColumn.put("name", displayNames.getOrDefault(originalName, fallbackColumnName(originalName)));
				safeColumns.add(safeColumn);
			}
			return safeColumns;
		}
		for (String column : originalColumns) {
			safeColumns.add(displayNames.getOrDefault(column, fallbackColumnName(column)));
		}
		return safeColumns;
	}

	private void renameRows(ObjectNode resultSet, String fieldName, List<String> originalColumns,
			Map<String, String> displayNames) {
		JsonNode rowsNode = resultSet.get(fieldName);
		if (rowsNode == null || !rowsNode.isArray()) {
			return;
		}
		ArrayNode safeRows = objectMapper.createArrayNode();
		for (JsonNode rowNode : rowsNode) {
			if (!rowNode.isObject()) {
				safeRows.add(rowNode);
				continue;
			}
			ObjectNode safeRow = objectMapper.createObjectNode();
			for (String column : originalColumns) {
				JsonNode value = rowNode.get(column);
				if (value != null) {
					safeRow.set(displayNames.getOrDefault(column, fallbackColumnName(column)), value);
				}
			}
			safeRows.add(safeRow);
		}
		resultSet.set(fieldName, safeRows);
	}

	private List<String> extractColumnNames(JsonNode columnsNode) {
		List<String> columns = new ArrayList<>();
		if (columnsNode == null || !columnsNode.isArray()) {
			return columns;
		}
		for (JsonNode item : columnsNode) {
			String value = item.isObject() ? item.path("name").asText("") : item.asText("");
			if (StringUtils.hasText(value)) {
				columns.add(value.trim());
			}
		}
		return columns;
	}

	private Map<String, String> buildColumnDisplayNames(List<String> columns, AnswerTraceExplainView explain) {
		SearchResultColumnNamer.Lexicon lexicon = SearchResultColumnNamer.lexicon()
			.addSemanticHits(explain == null ? List.of() : explain.getSemanticHits());
		return SearchResultColumnNamer.resolve(columns, explain == null ? null : explain.getSql(), lexicon);
	}

	private ReplacementPlan buildReplacementPlan(AnswerTraceExplainView explain) {
		Map<String, String> replacements = new LinkedHashMap<>();
		if (explain == null) {
			return new ReplacementPlan(replacements);
		}
		Map<String, String> semanticNames = semanticReplacementMap(explain);
		addReplacement(replacements, explain.getDatasource(), "业务数据");
		addReplacement(replacements, explain.getSql(), "内部查询");
		addSensitiveTokens(replacements, explain.getUsedTables(), "业务对象");
		if (explain.getUsedColumns() != null) {
			for (String column : explain.getUsedColumns()) {
				if (isSensitivePhysicalName(column)) {
					addReplacement(replacements, column,
							firstText(displayNameForSemanticColumn(column, semanticNames), knownDisplayName(column),
									fallbackColumnName(column)));
				}
			}
		}
		if (explain.getSemanticHits() != null) {
			for (SemanticHitView hit : explain.getSemanticHits()) {
				String semanticName = semanticDisplayName(hit);
				if (isSensitivePhysicalName(hit.getTableName())) {
					addReplacement(replacements, hit.getTableName(), "业务对象");
				}
				if (isSensitivePhysicalName(hit.getColumnName())) {
					addReplacement(replacements, hit.getColumnName(),
							firstText(semanticName, knownDisplayName(hit.getColumnName()), fallbackColumnName(hit.getColumnName())));
				}
			}
		}
		if (explain.getToolSteps() != null) {
			for (ToolStepView step : explain.getToolSteps()) {
				addReplacement(replacements, step.getToolName(), "内部处理");
				addReplacement(replacements, step.getDatasource(), "业务数据");
				if (!BUSINESS_FAILED.equals(step.getErrorCode())) {
					addReplacement(replacements, step.getDetail(), "内部处理细节");
				}
				addReplacement(replacements, step.getInputSummary(), "内部处理输入");
				addReplacement(replacements, step.getOutputSummary(), "内部处理输出");
				if (!BUSINESS_FAILED.equals(step.getErrorCode())) {
					addReplacement(replacements, step.getErrorMessage(), "内部处理异常");
				}
			}
		}
		return new ReplacementPlan(sortByLength(replacements));
	}

	private Map<String, String> semanticReplacementMap(AnswerTraceExplainView explain) {
		Map<String, String> names = new LinkedHashMap<>();
		if (explain == null || explain.getSemanticHits() == null) {
			return names;
		}
		for (SemanticHitView hit : explain.getSemanticHits()) {
			if (hit == null) {
				continue;
			}
			String display = semanticDisplayName(hit);
			if (StringUtils.hasText(hit.getColumnName()) && StringUtils.hasText(display)) {
				names.put(normalizeToken(hit.getColumnName()), display);
				if (StringUtils.hasText(hit.getTableName())) {
					names.put(normalizeToken(hit.getTableName() + "." + hit.getColumnName()), display);
				}
			}
		}
		return names;
	}

	private String semanticDisplayName(SemanticHitView hit) {
		if (hit == null) {
			return "";
		}
		return firstShortText(hit.getBusinessName(), hit.getColumnComment(), firstSynonym(hit.getSynonyms()),
				hit.getBusinessDescription(), hit.getRelationHint());
	}

	private String displayNameForSemanticColumn(String column, Map<String, String> semanticNames) {
		if (!StringUtils.hasText(column) || semanticNames == null || semanticNames.isEmpty()) {
			return "";
		}
		String exact = semanticNames.get(normalizeToken(column));
		if (StringUtils.hasText(exact)) {
			return exact;
		}
		int dotIndex = column.lastIndexOf('.');
		if (dotIndex >= 0 && dotIndex + 1 < column.length()) {
			return semanticNames.getOrDefault(normalizeToken(column.substring(dotIndex + 1)), "");
		}
		return "";
	}

	private Map<String, String> sortByLength(Map<String, String> replacements) {
		Map<String, String> sorted = new LinkedHashMap<>();
		replacements.entrySet()
			.stream()
			.sorted(Map.Entry.<String, String>comparingByKey(Comparator.comparingInt(String::length)).reversed())
			.forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
		return sorted;
	}

	private String replaceToken(String text, String token, String replacement) {
		if (!StringUtils.hasText(text) || !StringUtils.hasText(token)) {
			return text;
		}
		String safeReplacement = StringUtils.hasText(replacement) ? replacement : "业务信息";
		if (isIdentifierToken(token)) {
			return Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(token) + "(?![A-Za-z0-9_])")
				.matcher(text)
				.replaceAll(safeReplacement);
		}
		return text.replace(token, safeReplacement);
	}

	private void addSensitiveTokens(Map<String, String> replacements, List<String> values, String replacement) {
		if (values != null) {
			values.stream()
				.filter(this::isSensitivePhysicalName)
				.forEach(value -> addReplacement(replacements, value, replacement));
		}
	}

	private void addReplacement(Map<String, String> replacements, String token, String replacement) {
		if (!StringUtils.hasText(token)) {
			return;
		}
		String trimmed = token.trim();
		if (trimmed.length() > 500) {
			return;
		}
		if (trimmed.length() <= 2 && isIdentifierToken(trimmed)) {
			return;
		}
		replacements.putIfAbsent(trimmed, replacement);
	}

	private void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String key) {
		if (source.containsKey(key)) {
			target.put(key, source.get(key));
		}
	}

	private int copyInteractionMetadata(Map<String, Object> metadata, Map<String, Object> sanitized,
			AnswerTraceExplainView explain) {
		int wrapperCount = 0;
		String presentWrapperKey = null;
		for (String wrapperKey : INTERACTION_WRAPPER_KEYS) {
			if (metadata.containsKey(wrapperKey)) {
				wrapperCount++;
				presentWrapperKey = wrapperKey;
			}
		}
		if (wrapperCount != 1) {
			return wrapperCount;
		}
		Map<String, Object> safeInteraction = sanitizeInteractionMetadata(metadata.get(presentWrapperKey), presentWrapperKey,
				explain);
		if (safeInteraction != null) {
			sanitized.put(presentWrapperKey, safeInteraction);
		}
		return wrapperCount;
	}

	private Map<String, Object> sanitizeInteractionMetadata(Object value, String wrapperKey,
			AnswerTraceExplainView explain) {
		if (!(value instanceof Map<?, ?> interaction)) {
			return null;
		}
		boolean confirmation = "confirmation".equals(wrapperKey) && "confirm/v1".equals(interaction.get("schemaVersion"));
		Map<String, Object> safeInteraction = new LinkedHashMap<>();
		copyInteractionString(interaction, safeInteraction, "schemaVersion", false, explain);
		copyInteractionString(interaction, safeInteraction, "clarificationId", false, explain);
		copyInteractionString(interaction, safeInteraction, "title", true, explain);
		copyInteractionString(interaction, safeInteraction, "prompt", true, explain);
		if (interaction.get("options") instanceof List<?> options) {
			List<Map<String, Object>> safeOptions = new ArrayList<>();
			for (Object item : options.stream().limit(MAX_SUGGESTED_REPLY_OPTIONS).toList()) {
				if (!(item instanceof Map<?, ?> option)) {
					continue;
				}
				Map<String, Object> safeOption = new LinkedHashMap<>();
				copyInteractionString(option, safeOption, "id", false, explain);
				copyInteractionString(option, safeOption, "label", true, explain);
				copyInteractionString(option, safeOption, "description", true, explain);
				if (!safeOption.isEmpty()) {
					safeOptions.add(safeOption);
				}
			}
			safeInteraction.put("options", safeOptions);
		}
		if (interaction.get("allowFreeText") instanceof Boolean allowFreeText) {
			safeInteraction.put("allowFreeText", allowFreeText);
		}
		copyInteractionString(interaction, safeInteraction, "expiresAt", false, explain);
		if (confirmation) {
			copyInteractionString(interaction, safeInteraction, "riskLevel", false, explain);
			copyInteractionString(interaction, safeInteraction, "summary", true, explain);
		}
		if (confirmation && interaction.get("planSteps") instanceof List<?> planSteps) {
			List<Map<String, Object>> safePlanSteps = new ArrayList<>();
			for (Object item : planSteps.stream().limit(MAX_SUGGESTED_REPLY_OPTIONS).toList()) {
				if (!(item instanceof Map<?, ?> planStep)) {
					continue;
				}
				Map<String, Object> safePlanStep = new LinkedHashMap<>();
				copyInteractionString(planStep, safePlanStep, "name", true, explain);
				copyInteractionString(planStep, safePlanStep, "description", true, explain);
				if (confirmation) {
					copyInteractionString(planStep, safePlanStep, "riskLevel", false, explain);
				}
				if (!safePlanStep.isEmpty()) {
					safePlanSteps.add(safePlanStep);
				}
			}
			if (!safePlanSteps.isEmpty()) {
				safeInteraction.put("planSteps", safePlanSteps);
			}
		}
		return safeInteraction.isEmpty() ? null : safeInteraction;
	}

	private void copyInteractionString(Map<?, ?> source, Map<String, Object> target, String key, boolean sanitize,
			AnswerTraceExplainView explain) {
		if (!(source.get(key) instanceof String text)) {
			return;
		}
		target.put(key, sanitize ? sanitizeText(text, explain) : text);
	}

	private void copyClarifyMetadata(Map<String, Object> metadata, Map<String, Object> sanitized,
			AnswerTraceExplainView explain) {
		for (String key : List.of("clarifyRequired", "riskLevel", "missingDimensions", "followUpQuestions",
				"suggestedAssumptions", "summary", "originalQuery")) {
			Object value = metadata.get(key);
			if (value == null) {
				continue;
			}
			if (value instanceof String text) {
				sanitized.put(key, sanitizeText(text, explain));
			}
			else if (value instanceof List<?> list) {
				sanitized.put(key, list.stream().map(item -> sanitizeText(String.valueOf(item), explain)).toList());
			}
			else {
				sanitized.put(key, value);
			}
		}
	}

	private void copyAnalysisFollowUps(Map<String, Object> metadata, Map<String, Object> sanitized,
			AnswerTraceExplainView explain) {
		Object raw = metadata.get(ANALYSIS_FOLLOW_UPS_KEY);
		if (!(raw instanceof List<?> items) || items.isEmpty()) {
			return;
		}
		List<Map<String, Object>> safe = new ArrayList<>();
		for (Object item : items) {
			if (!(item instanceof Map<?, ?> map)) {
				continue;
			}
			String type = stringValue(map.get("type"));
			if (!"DRILL".equals(type) && !"START_FLOW".equals(type) && !"ASK_WRITE".equals(type)) {
				continue;
			}
			Map<String, Object> followUp = new LinkedHashMap<>();
			followUp.put("type", type);
			for (String key : List.of("label", "value", "query", "grain", "skillCode", "toolName")) {
				Object value = map.get(key);
				if (value instanceof String text && StringUtils.hasText(text)) {
					followUp.put(key, sanitizeText(text, explain));
				}
				else if ("confirm".equals(key) && value instanceof Boolean flag) {
					followUp.put(key, flag);
				}
			}
			if (map.get("confirm") instanceof Boolean flag) {
				followUp.put("confirm", flag);
			}
			if (followUp.size() > 1) {
				safe.add(followUp);
			}
		}
		if (!safe.isEmpty()) {
			sanitized.put(ANALYSIS_FOLLOW_UPS_KEY, List.copyOf(safe));
		}
	}

	private void copySuggestedReplies(Map<String, Object> metadata, Map<String, Object> sanitized,
			AnswerTraceExplainView explain) {
		Object suggestedReplies = sanitizeSuggestedReplies(metadata.get(SUGGESTED_REPLIES_KEY), explain);
		if (suggestedReplies != null) {
			sanitized.put(SUGGESTED_REPLIES_KEY, suggestedReplies);
		}
	}

	private Map<String, Object> sanitizeSuggestedReplies(Object value, AnswerTraceExplainView explain) {
		if (!(value instanceof Map<?, ?> replies)) {
			return null;
		}
		if (!SUGGESTED_REPLIES_SCHEMA_VERSION.equals(replies.get("schemaVersion"))) {
			return null;
		}
		String source = stringValue(replies.get("source"));
		if (!SUGGESTED_REPLIES_SOURCES.contains(source)) {
			return null;
		}
		if (!"confirm".equals(replies.get("submitMode"))) {
			return null;
		}
		Object rawGroups = replies.get("groups");
		if (!(rawGroups instanceof List<?> groups)) {
			return null;
		}
		List<Map<String, Object>> safeGroups = new ArrayList<>();
		for (Object item : groups.stream().limit(MAX_SUGGESTED_REPLY_GROUPS).toList()) {
			Map<String, Object> safeGroup = sanitizeSuggestedReplyGroup(item, explain);
			if (safeGroup != null) {
				safeGroups.add(safeGroup);
			}
		}
		if (safeGroups.isEmpty()) {
			return null;
		}
		Map<String, Object> safeReplies = new LinkedHashMap<>();
		safeReplies.put("schemaVersion", SUGGESTED_REPLIES_SCHEMA_VERSION);
		safeReplies.put("source", source);
		safeReplies.put("submitMode", "confirm");
		String displayMode = sanitizeSuggestedReplyDisplayMode(replies.get("displayMode"));
		if (StringUtils.hasText(displayMode)) {
			safeReplies.put("displayMode", displayMode);
		}
		safeReplies.put("groups", safeGroups);
		return safeReplies;
	}

	private Map<String, Object> sanitizeSuggestedReplyGroup(Object value, AnswerTraceExplainView explain) {
		if (!(value instanceof Map<?, ?> group)) {
			return null;
		}
		if (!Integer.valueOf(1).equals(numberAsInteger(group.get("maxSelect")))) {
			return null;
		}
		String groupId = sanitizeSuggestedReplyText(group.get("groupId"), explain);
		String title = sanitizeSuggestedReplyText(group.get("title"), explain);
		String displayMode = sanitizeSuggestedReplyDisplayMode(group.get("displayMode"));
		Object rawOptions = group.get("options");
		if (!StringUtils.hasText(groupId) || !StringUtils.hasText(title) || !(rawOptions instanceof List<?> options)) {
			return null;
		}
		List<Map<String, Object>> safeOptions = new ArrayList<>();
		for (Object item : options.stream().limit(MAX_SUGGESTED_REPLY_OPTIONS).toList()) {
			Map<String, Object> safeOption = sanitizeSuggestedReplyOption(item, explain);
			if (safeOption != null) {
				safeOptions.add(safeOption);
			}
		}
		if (safeOptions.isEmpty() && !"narrow_required".equals(displayMode)) {
			return null;
		}
		Map<String, Object> safeGroup = new LinkedHashMap<>();
		safeGroup.put("groupId", groupId);
		safeGroup.put("title", title);
		if (group.get("required") instanceof Boolean required) {
			safeGroup.put("required", required);
		}
		safeGroup.put("maxSelect", 1);
		if (StringUtils.hasText(displayMode)) {
			safeGroup.put("displayMode", displayMode);
		}
		if ("single".equals(group.get("selectionMode"))) {
			safeGroup.put("selectionMode", "single");
		}
		Integer candidateCount = numberAsInteger(group.get("candidateCount"));
		if (candidateCount != null && candidateCount >= 0) {
			safeGroup.put("candidateCount", candidateCount);
		}
		List<Map<String, Object>> rowFields = sanitizeSuggestedReplyRowFields(group.get("rowFields"), explain);
		if (!rowFields.isEmpty()) {
			safeGroup.put("rowFields", rowFields);
		}
		safeGroup.put("options", safeOptions);
		return safeGroup;
	}

	private String sanitizeSuggestedReplyDisplayMode(Object value) {
		String text = stringValue(value).trim();
		return SUGGESTED_REPLY_DISPLAY_MODES.contains(text) ? text : "";
	}

	private List<Map<String, Object>> sanitizeSuggestedReplyRowFields(Object value, AnswerTraceExplainView explain) {
		if (!(value instanceof List<?> fields)) {
			return List.of();
		}
		List<Map<String, Object>> safeFields = new ArrayList<>();
		for (Object item : fields.stream().limit(8).toList()) {
			if (!(item instanceof Map<?, ?> field)) {
				continue;
			}
			String fieldCode = sanitizeSuggestedReplyText(field.get("fieldCode"), explain);
			String label = sanitizeSuggestedReplyText(field.get("label"), explain);
			if (!StringUtils.hasText(fieldCode) || !StringUtils.hasText(label)) {
				continue;
			}
			safeFields.add(Map.of("fieldCode", fieldCode, "label", label));
		}
		return safeFields;
	}

	private Map<String, Object> sanitizeSuggestedReplyOption(Object value, AnswerTraceExplainView explain) {
		if (!(value instanceof Map<?, ?> option)) {
			return null;
		}
		String optionId = sanitizeSuggestedReplyText(option.get("optionId"), explain);
		String label = sanitizeSuggestedReplyText(option.get("label"), explain);
		String optionValue = sanitizeSuggestedReplyText(option.get("value"), explain);
		if (!StringUtils.hasText(optionId) || !StringUtils.hasText(label) || !StringUtils.hasText(optionValue)) {
			return null;
		}
		Map<String, Object> safeOption = new LinkedHashMap<>();
		safeOption.put("optionId", optionId);
		safeOption.put("label", label);
		safeOption.put("value", optionValue);
		String summary = sanitizeSuggestedReplyText(option.get("summary"), explain);
		if (StringUtils.hasText(summary)) {
			safeOption.put("summary", summary);
		}
		Object payload = sanitizeSuggestedReplyPayload(option.get("payload"), explain,
				MAX_SUGGESTED_REPLY_PAYLOAD_DEPTH);
		if (payload instanceof Map<?, ?> map && !map.isEmpty()) {
			safeOption.put("payload", payload);
		}
		return safeOption;
	}

	private Object sanitizeSuggestedReplyPayload(Object value, AnswerTraceExplainView explain, int depth) {
		if (value == null || depth < 0) {
			return null;
		}
		if (value instanceof String text) {
			return truncateText(sanitizeText(text, explain), MAX_SUGGESTED_REPLY_TEXT_LENGTH);
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value;
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> safeMap = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet().stream().limit(MAX_SUGGESTED_REPLY_PAYLOAD_KEYS).toList()) {
				String key = sanitizeSuggestedReplyText(entry.getKey(), explain);
				if (isInternalSuggestedReplyPayloadKey(key)) {
					continue;
				}
				Object safeValue = sanitizeSuggestedReplyPayload(entry.getValue(), explain, depth - 1);
				if (StringUtils.hasText(key) && safeValue != null) {
					safeMap.put(key, safeValue);
				}
			}
			return safeMap;
		}
		if (value instanceof List<?> list) {
			return list.stream()
				.limit(MAX_SUGGESTED_REPLY_OPTIONS)
				.map(item -> sanitizeSuggestedReplyPayload(item, explain, depth - 1))
				.filter(item -> item != null)
				.toList();
		}
		return null;
	}

	private boolean isInternalSuggestedReplyPayloadKey(String key) {
		if (!StringUtils.hasText(key)) {
			return false;
		}
		String normalized = key.trim();
		return "rawData".equals(normalized) || "fillParameters".equals(normalized)
				|| "currentParameters".equals(normalized) || "recordValue".equals(normalized)
				|| "resourceKey".equals(normalized) || isBusinessIdKey(normalized);
	}

	private boolean isBusinessIdKey(String key) {
		String lower = key.toLowerCase(Locale.ROOT);
		if ("id".equals(lower) || "ids".equals(lower) || lower.endsWith("_id") || lower.endsWith("_ids")
				|| lower.endsWith("-id") || lower.endsWith("-ids") || key.endsWith("Id") || key.endsWith("Ids")
				|| key.endsWith("ID") || key.endsWith("IDs")) {
			return !Set.of("optionId", "selectionId", "selectionToken", "groupId").contains(key);
		}
		if (!lower.endsWith("id") && !lower.endsWith("ids")) {
			return false;
		}
		return Stream.of("company", "customer", "project", "demand", "order", "bill", "product", "site",
				"salesmanager", "客户", "项目", "需求", "订单", "单据", "商品", "产品", "网点", "客户经理")
			.anyMatch(lower::contains);
	}

	private String sanitizeSuggestedReplyText(Object value, AnswerTraceExplainView explain) {
		if (value == null) {
			return "";
		}
		return truncateText(sanitizeText(String.valueOf(value), explain), MAX_SUGGESTED_REPLY_TEXT_LENGTH);
	}

	private String truncateText(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}

	private Integer numberAsInteger(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		return null;
	}

	private String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private String knownDisplayName(String column) {
		if (!StringUtils.hasText(column)) {
			return "";
		}
		return KNOWN_DISPLAY_NAMES.getOrDefault(column.trim().toLowerCase(Locale.ROOT), "");
	}

	private Map<String, String> knownPhysicalNameReplacements() {
		Map<String, String> replacements = new LinkedHashMap<>();
		KNOWN_DISPLAY_NAMES.forEach((physical, display) -> {
			if (physical != null && physical.contains("_") && StringUtils.hasText(display)) {
				replacements.put(physical, display);
			}
		});
		return sortByLength(replacements);
	}

	private String fallbackColumnName(String column) {
		if (!StringUtils.hasText(column)) {
			return "分类";
		}
		String resolved = SearchResultColumnNamer.resolve(List.of(column), null, SearchResultColumnNamer.lexicon())
			.get(column);
		if (isUserFacingColumnName(resolved)) {
			return resolved;
		}
		return firstText(knownDisplayName(column), "分类");
	}

	private String userFacingColumnName(String column, Map<String, String> displayNames) {
		String display = displayNames == null ? "" : displayNames.getOrDefault(column, "");
		if (isUserFacingColumnName(display)) {
			return display;
		}
		return fallbackColumnName(column);
	}

	/**
	 * 用户面列名唯一化：多列兜底到同一个中文名时按序补 2/3，避免列头与 data keys 互相覆盖。
	 */
	private String uniqueDisplayName(String display, Set<String> used) {
		String base = StringUtils.hasText(display) ? display.trim() : "分类";
		String candidate = base;
		int index = 2;
		while (!used.add(candidate)) {
			candidate = base + index++;
		}
		return candidate;
	}

	private boolean isUserFacingColumnName(String name) {
		return StringUtils.hasText(name) && !name.contains("_") && !name.contains(".");
	}

	private boolean isSensitivePhysicalName(String value) {
		if (!StringUtils.hasText(value)) {
			return false;
		}
		String trimmed = value.trim();
		return trimmed.contains(".") || trimmed.contains("_") || isIdentifierToken(trimmed) || isLikelyPhysicalColumn(trimmed);
	}

	private boolean isIdentifierToken(String token) {
		return token != null && token.matches("[A-Za-z_][A-Za-z0-9_.$-]*");
	}

	private String normalizeToken(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private boolean isLikelyPhysicalColumn(String column) {
		String normalized = normalizeToken(column).replace(" ", "");
		return normalized.equals("id") || normalized.endsWith("id") || normalized.endsWith("code")
				|| normalized.endsWith("no") || normalized.contains("time") || normalized.contains("date")
				|| normalized.contains("amount") || normalized.contains("amt") || normalized.contains("count")
				|| normalized.contains("cnt") || normalized.contains("num") || normalized.contains("qty")
				|| normalized.contains("price") || normalized.contains("cost") || normalized.contains("fee")
				|| normalized.contains("rate") || normalized.contains("type") || normalized.contains("status")
				|| normalized.contains("deleted");
	}

	private String firstSynonym(String synonyms) {
		if (!StringUtils.hasText(synonyms)) {
			return "";
		}
		for (String item : synonyms.split("[,，;；/|、\\s]+")) {
			if (StringUtils.hasText(item)) {
				return item.trim();
			}
		}
		return "";
	}

	private String firstShortText(String... values) {
		if (values == null) {
			return "";
		}
		for (String value : values) {
			String normalized = normalizeDisplayName(value);
			if (StringUtils.hasText(normalized)) {
				return normalized;
			}
		}
		return "";
	}

	private String normalizeDisplayName(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		String trimmed = value.trim().replace("`", "");
		String[] parts = trimmed.split("[,，;；。\\r\\n:：\\[\\]【】()（）]", 2);
		String displayName = parts.length == 0 ? trimmed : parts[0].trim();
		if (!StringUtils.hasText(displayName) || displayName.length() > 30) {
			return "";
		}
		if (displayName.toLowerCase(Locale.ROOT).contains("sql") || displayName.contains("_")
				|| displayName.contains(".")) {
			return "";
		}
		if (isIdentifierToken(displayName) && !isSafeBusinessAcronym(displayName)) {
			return "";
		}
		return displayName;
	}

	private boolean isSafeBusinessAcronym(String value) {
		String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z][A-Z0-9]{1,9}") && !List.of("ID", "NO", "SQL", "DB", "DDL", "DML")
			.contains(normalized);
	}

	private String firstText(String... values) {
		if (values == null) {
			return "";
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private record ReplacementPlan(Map<String, String> replacements) {
	}

}
