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
package com.sn68.agent.dataagent.service.interaction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRequestSnapshotSupport;
import com.sn68.agent.dataagent.util.JsonUtil;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 把候选记录投影为前端可展示的 suggestedReplies 结构。
 */
@Slf4j
@Service
public class SuggestedReplyProjectionService {

	public static final String SCHEMA_VERSION = "suggested-replies/v1";

	public static final String SOURCE_AGENT_RUNTIME = "agent-runtime";

	private static final String DISPLAY_MODE_AUTO_FILL = "auto_fill";

	private static final String DISPLAY_MODE_BUTTONS = "buttons";

	private static final String DISPLAY_MODE_TABLE = "table";

	private static final String DISPLAY_MODE_NARROW_REQUIRED = "narrow_required";

	private static final int BUTTON_LIMIT = 3;

	private static final int TABLE_LIMIT = 8;

	private static final int MAX_ROW_FIELDS = 6;

	private static final long SELECTION_TOKEN_TTL_MILLIS = 30L * 60L * 1000L;

	private static final char[] TOKEN_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
		.toCharArray();

	private static final ObjectMapper OBJECT_MAPPER = JsonUtil.getObjectMapper();

	private static final TypeReference<Object> JSON_VALUE_TYPE = new TypeReference<>() {
	};

	private final SecureRandom secureRandom = new SecureRandom();

	private final Map<String, SelectionEntry> selectionTokens = new ConcurrentHashMap<>();

	/**
	 * 创建SuggestedReplyProjection。
	 */
	public Map<String, Object> buildCandidateQueryResult(String source, String groupId, String title, String resourceKey,
			Map<String, Object> rawResult, Map<String, Object> currentParameters) {
		return buildCandidateQueryResult(source, groupId, title, resourceKey, rawResult, currentParameters,
				currentParameters);
	}

	/**
	 * 创建SuggestedReplyProjection。
	 */
	public Map<String, Object> buildCandidateQueryResult(String source, String groupId, String title, String resourceKey,
			Map<String, Object> rawResult, Map<String, Object> currentParameters,
			Map<String, Object> tokenContext) {
		Map<String, Object> result = new LinkedHashMap<>(rawResult == null ? Map.of() : rawResult);
		List<Map<String, Object>> records = normalizeCandidateRecords(rawResult);
		result.put("records", records);
		Map<String, Object> suggestedReplies = buildCandidateReplies(source, groupId, title, resourceKey, records,
				currentParameters, tokenContext);
		if (suggestedReplies != null) {
			result.put("suggestedReplies", suggestedReplies);
		}
		return result;
	}

	/**
	 * 处理SuggestedReplyProjection。
	 */
	public List<Map<String, Object>> normalizeCandidateRecords(Object rawResult) {
		Object candidatePayload = candidatePayload(rawResult);
		Object listValue = firstListValue(candidatePayload);
		if (listValue instanceof Collection<?> values) {
			return values.stream().map(this::normalizeCandidateRecord).toList();
		}
		if (candidatePayload == null || (candidatePayload instanceof Map<?, ?> map && map.isEmpty())) {
			return List.of();
		}
		return List.of(normalizeCandidateRecord(candidatePayload));
	}

	/**
	 * 创建SuggestedReplyProjection。
	 */
	public Map<String, Object> buildCandidateReplies(String source, String groupId, String title, String resourceKey,
			List<Map<String, Object>> records, Map<String, Object> currentParameters) {
		return buildCandidateReplies(source, groupId, title, resourceKey, records, currentParameters, currentParameters);
	}

	/**
	 * 创建SuggestedReplyProjection。
	 */
	public Map<String, Object> buildCandidateReplies(String source, String groupId, String title, String resourceKey,
			List<Map<String, Object>> records, Map<String, Object> currentParameters,
			Map<String, Object> tokenContext) {
		if (records == null || records.isEmpty()) {
			return null;
		}
		String displayMode = displayMode(records.size());
		Map<String, Object> group = new LinkedHashMap<>();
		group.put("groupId", firstText(groupId, "candidate-records"));
		group.put("title", title(records.size(), title, displayMode));
		group.put("required", false);
		group.put("maxSelect", 1);
		group.put("displayMode", displayMode);
		group.put("selectionMode", "single");
		group.put("candidateCount", records.size());
		group.put("rowFields", rowFields(records));
		group.put("options", DISPLAY_MODE_NARROW_REQUIRED.equals(displayMode) ? List.of()
				: records.stream()
					.limit(TABLE_LIMIT)
					.map(record -> option(resourceKey, record, tokenContext))
					.toList());

		Map<String, Object> replies = new LinkedHashMap<>();
		replies.put("schemaVersion", SCHEMA_VERSION);
		replies.put("source", firstText(source, SOURCE_AGENT_RUNTIME));
		replies.put("submitMode", "confirm");
		replies.put("displayMode", displayMode);
		replies.put("groups", List.of(group));
		return replies;
	}

	/**
	 * 查询SuggestedReplyProjection。
	 */
	public Optional<Map<String, Object>> resolveSelectionToken(String token, Map<String, Object> tokenContext) {
		if (!StringUtils.hasText(token)) {
			return Optional.empty();
		}
		SelectionEntry entry = selectionTokens.get(token.trim());
		if (entry == null) {
			return Optional.empty();
		}
		if (entry.expired()) {
			selectionTokens.remove(token.trim());
			return Optional.empty();
		}
		String actualResourceKey = firstText(stringValue(tokenContext == null ? null : tokenContext.get("resourceKey")),
				entry.binding().resourceKey());
		if (!entry.binding().matches(binding(actualResourceKey, tokenContext))) {
			return Optional.empty();
		}
		return Optional.of(new LinkedHashMap<>(entry.rawData()));
	}

	private String displayMode(int count) {
		if (count == 1) {
			return DISPLAY_MODE_AUTO_FILL;
		}
		if (count <= BUTTON_LIMIT) {
			return DISPLAY_MODE_BUTTONS;
		}
		if (count <= TABLE_LIMIT) {
			return DISPLAY_MODE_TABLE;
		}
		return DISPLAY_MODE_NARROW_REQUIRED;
	}

	private Object firstListValue(Object rawResult) {
		if (rawResult instanceof Collection<?>) {
			return rawResult;
		}
		if (!(rawResult instanceof Map<?, ?> map) || map.isEmpty()) {
			return null;
		}
		for (String key : List.of("items", "records", "data", "list", "rows", "options", "result")) {
			Object value = map.get(key);
			if (value instanceof Collection<?>) {
				return value;
			}
		}
		for (String key : List.of("items", "records", "data", "list", "rows", "options", "result")) {
			Object nested = firstListValue(map.get(key));
			if (nested instanceof Collection<?>) {
				return nested;
			}
		}
		return null;
	}

	private Object candidatePayload(Object rawResult) {
		Object parsed = embeddedJsonPayload(rawResult);
		return parsed == null ? rawResult : parsed;
	}

	private Object embeddedJsonPayload(Object value) {
		if (value instanceof CharSequence sequence) {
			return parseJsonValue(sequence.toString());
		}
		if (value instanceof Collection<?> collection) {
			return parseContentText(collection);
		}
		if (!(value instanceof Map<?, ?> map)) {
			return null;
		}
		Map<String, Object> data = copyStringKeyMap(map);
		Object contentPayload = parseContentText(data.get("content"));
		if (contentPayload != null) {
			return contentPayload;
		}
		if (!canParseTextPayload(data)) {
			return null;
		}
		for (String key : List.of("text", "message")) {
			Object parsed = parseJsonValue(stringValue(data.get(key)));
			if (parsed != null) {
				return parsed;
			}
		}
		return null;
	}

	private Object parseContentText(Object value) {
		if (!(value instanceof Collection<?> collection)) {
			return null;
		}
		for (Object item : collection) {
			if (item instanceof Map<?, ?> map) {
				Map<String, Object> contentItem = copyStringKeyMap(map);
				Object parsed = parseJsonValue(stringValue(contentItem.get("text")));
				if (parsed != null) {
					return parsed;
				}
				continue;
			}
			Object parsed = parseJsonValue(stringValue(item));
			if (parsed != null) {
				return parsed;
			}
		}
		return null;
	}

	private Object parseJsonValue(String text) {
		if (!StringUtils.hasText(text)) {
			return null;
		}
		String trimmed = text.trim();
		boolean jsonObject = trimmed.startsWith("{") && trimmed.endsWith("}");
		boolean jsonArray = trimmed.startsWith("[") && trimmed.endsWith("]");
		if (!jsonObject && !jsonArray) {
			return null;
		}
		try {
			return OBJECT_MAPPER.readValue(trimmed, JSON_VALUE_TYPE);
		}
		catch (Exception ex) {
			// 已按 {}/[] 判定为 JSON 字面量，仍解析失败说明内容确实坏了
			log.warn("Suggested reply payload looks like JSON but cannot be parsed, dropping it. length={}",
					trimmed.length(), ex);
			return null;
		}
	}

	private boolean canParseTextPayload(Map<String, Object> data) {
		if (data == null || data.isEmpty()) {
			return false;
		}
		if (Stream.of("mcpSuccess", "toolError", "resourceType", "resourceKey", "serverCode", "toolName", "isError")
			.anyMatch(data::containsKey)) {
			return true;
		}
		return !looksLikeCandidateRecord(data);
	}

	private boolean looksLikeCandidateRecord(Map<String, Object> data) {
		return Stream.of("rawData", "label", "optionLabel", "title", "name", "demandNo", "orderNo", "billNo",
				"companyName", "customerName", "projectName", "oneProjectName", "twoProjectName", "displayFields")
			.anyMatch(data::containsKey);
	}

	private Map<String, Object> normalizeCandidateRecord(Object value) {
		Map<String, Object> record = new LinkedHashMap<>();
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> envelope = copyStringKeyMap(map);
			Map<String, Object> rawData = candidateRawData(envelope);
			Map<String, Object> displaySource = envelope.containsKey("displayFields") ? envelope : rawData;
			Set<String> internalFields = configuredFields(displaySource.get("internalFields"), rawData.get("internalFields"));
			Set<String> contextFields = configuredFields(displaySource.get("contextFields"), rawData.get("contextFields"));
			String label = firstText(stringValue(envelope.get("label")), stringValue(envelope.get("optionLabel")),
					stringValue(envelope.get("title")), stringValue(envelope.get("name")),
					firstDisplayFieldValue(displaySource, internalFields, contextFields),
					publicRawText(rawData, "demandNo", internalFields, contextFields),
					publicRawText(rawData, "orderNo", internalFields, contextFields),
					publicRawText(rawData, "billNo", internalFields, contextFields),
					publicRawText(rawData, "code", internalFields, contextFields),
					publicRawText(rawData, "no", internalFields, contextFields),
					firstBusinessLabelValue(rawData, internalFields, contextFields), "Candidate record");
			String optionValue = firstText(publicRawText(rawData, "code", internalFields, contextFields),
					publicRawText(rawData, "no", internalFields, contextFields),
					publicRawText(rawData, "demandNo", internalFields, contextFields),
					publicRawText(rawData, "orderNo", internalFields, contextFields),
					publicRawText(rawData, "billNo", internalFields, contextFields), label);
			record.put("label", label);
			record.put("value", optionValue);
			record.put("summary", firstText(stringValue(envelope.get("summary")), candidateSummary(rawData, label)));
			record.put("displayFields", candidateDisplayFields(displaySource, rawData, label));
			record.put("rawData", rawData);
			return record;
		}
		String text = stringValue(value);
		record.put("label", text);
		record.put("value", text);
		record.put("summary", "");
		record.put("rawData", Map.of("value", text));
		return record;
	}

	private Map<String, Object> candidateRawData(Map<String, Object> envelope) {
		Object rawData = envelope.get("rawData");
		if (rawData instanceof Map<?, ?> rawMap) {
			return copyStringKeyMap(rawMap);
		}
		return envelope;
	}

	private String candidateSummary(Map<String, Object> rawData, String label) {
		String configured = firstText(stringValue(rawData.get("summary")), stringValue(rawData.get("description")),
				stringValue(rawData.get("remark")));
		if (StringUtils.hasText(configured)) {
			return configured;
		}
		List<String> parts = new ArrayList<>();
		for (String key : List.of("companyName", "customerName", "projectName", "createTime", "createdAt", "orderDate",
				"demandDate", "billDate")) {
			String value = stringValue(rawData.get(key));
			if (StringUtils.hasText(value) && !value.equals(label) && parts.stream().noneMatch(value::equals)) {
				parts.add(value.trim());
			}
			if (parts.size() >= 3) {
				break;
			}
		}
		return String.join(" / ", parts);
	}

	private List<Map<String, Object>> candidateDisplayFields(Map<String, Object> displaySource,
			Map<String, Object> rawData, String label) {
		Set<String> internalFields = configuredFields(displaySource.get("internalFields"), rawData.get("internalFields"));
		Set<String> contextFields = configuredFields(displaySource.get("contextFields"), rawData.get("contextFields"));
		Object displayFields = displaySource.get("displayFields");
		if (displayFields instanceof Collection<?> fields) {
			return fields.stream()
				.filter(Map.class::isInstance)
				.map(item -> copyStringKeyMap((Map<?, ?>) item))
				.filter(item -> isPublicDisplayField(item, internalFields, contextFields))
				.toList();
		}
		List<Map<String, Object>> fields = new ArrayList<>();
		for (String key : List.of("demandNo", "orderNo", "billNo", "companyName", "customerName", "oneProjectName",
				"twoProjectName", "projectName", "typeName", "businessTypeName", "fromSiteName", "toSiteName",
				"demandNum", "createTime", "arrivalTime", "industryName", "salesManagerName")) {
			if (internalFields.contains(key) || contextFields.contains(key)) {
				continue;
			}
			String value = stringValue(rawData.get(key));
			if (!StringUtils.hasText(value) || value.equals(label)) {
				continue;
			}
			fields.add(Map.of("label", displayLabel(key), "value", value.trim()));
			if (fields.size() >= 6) {
				break;
			}
		}
		return fields;
	}

	private String firstBusinessLabelValue(Map<String, Object> rawData, Set<String> internalFields,
			Set<String> contextFields) {
		for (String key : List.of("companyName", "customerName", "oneProjectName", "twoProjectName", "projectName",
				"productName", "siteName", "name")) {
			String value = publicRawText(rawData, key, internalFields, contextFields);
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return "";
	}

	private Set<String> configuredFields(Object... values) {
		Set<String> fields = new LinkedHashSet<>();
		if (values == null) {
			return fields;
		}
		for (Object value : values) {
			if (!(value instanceof Collection<?> collection)) {
				continue;
			}
			collection.stream().map(this::stringValue).filter(StringUtils::hasText).map(String::trim).forEach(fields::add);
		}
		return fields;
	}

	private boolean isPublicDisplayField(Map<String, Object> field, Set<String> internalFields,
			Set<String> contextFields) {
		String key = firstText(stringValue(field.get("fieldCode")), stringValue(field.get("name")),
				stringValue(field.get("key")), stringValue(field.get("prop")));
		String label = stringValue(field.get("label"));
		if (isInternalField(key, label) || internalFields.contains(key) || contextFields.contains(key)) {
			return false;
		}
		String value = stringValue(field.get("value"));
		return StringUtils.hasText(value);
	}

	private String firstDisplayFieldValue(Map<String, Object> displaySource, Set<String> internalFields,
			Set<String> contextFields) {
		Object displayFields = displaySource == null ? null : displaySource.get("displayFields");
		if (!(displayFields instanceof Collection<?> fields)) {
			return "";
		}
		return fields.stream()
			.filter(Map.class::isInstance)
			.map(item -> copyStringKeyMap((Map<?, ?>) item))
			.filter(item -> isPublicDisplayField(item, internalFields, contextFields))
			.map(item -> stringValue(item.get("value")))
			.filter(StringUtils::hasText)
			.findFirst()
			.orElse("");
	}

	private String publicRawText(Map<String, Object> rawData, String key, Set<String> internalFields,
			Set<String> contextFields) {
		if (rawData == null || isInternalField(key, null) || internalFields.contains(key) || contextFields.contains(key)) {
			return "";
		}
		return stringValue(rawData.get(key));
	}

	private boolean isInternalField(String key, String label) {
		return isInternalFieldName(key) || isInternalFieldName(label);
	}

	private boolean isInternalFieldName(String value) {
		if (!StringUtils.hasText(value)) {
			return false;
		}
		String trimmed = value.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		return "id".equals(lower) || "ids".equals(lower) || trimmed.endsWith("Id") || trimmed.endsWith("Ids")
				|| trimmed.endsWith("ID") || trimmed.endsWith("IDs") || lower.endsWith("_id")
				|| lower.endsWith("_ids") || lower.endsWith("-id") || lower.endsWith("-ids")
				|| isKnownBusinessIdField(lower);
	}

	private boolean isKnownBusinessIdField(String lower) {
		if (!StringUtils.hasText(lower) || (!lower.endsWith("id") && !lower.endsWith("ids"))) {
			return false;
		}
		return Stream.of("company", "customer", "project", "demand", "order", "bill", "product", "site",
				"salesmanager", "客户", "项目", "需求", "订单", "单据", "商品", "产品", "网点", "客户经理")
			.anyMatch(lower::contains);
	}

	private String displayLabel(String key) {
		return switch (key) {
			case "demandNo" -> "单号";
			case "orderNo" -> "订单号";
			case "billNo" -> "单据号";
			case "companyName", "customerName" -> "客户";
			case "oneProjectName" -> "一级项目";
			case "twoProjectName" -> "二级项目";
			case "projectName" -> "项目";
			case "typeName" -> "需求类型";
			case "businessTypeName" -> "业务类型";
			case "fromSiteName" -> "发货网点";
			case "toSiteName" -> "收货网点";
			case "demandNum" -> "数量";
			case "createTime" -> "创建时间";
			case "arrivalTime" -> "到货时间";
			case "industryName" -> "行业";
			case "salesManagerName" -> "客户经理";
			default -> key;
		};
	}

	private String title(int count, String title, String displayMode) {
		if (DISPLAY_MODE_NARROW_REQUIRED.equals(displayMode)) {
			return "找到 " + count + " 条候选，请补充筛选条件";
		}
		if (StringUtils.hasText(title)) {
			return title.trim();
		}
		if (DISPLAY_MODE_AUTO_FILL.equals(displayMode)) {
			return "已找到唯一匹配候选";
		}
		return "请选择匹配记录";
	}

	private Map<String, Object> option(String resourceKey, Map<String, Object> record,
			Map<String, Object> tokenContext) {
		String label = firstText(stringValue(record.get("label")), stringValue(record.get("value")), "候选记录");
		String summary = firstText(stringValue(record.get("summary")), displaySummary(record), label);
		String selectionToken = createSelectionToken(resourceKey, record, tokenContext);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("action", "candidate_select");
		payload.put("selectionId", selectionToken);
		payload.put("selectionToken", selectionToken);
		payload.put("displayText", selectionText(label, summary, record));
		payload.put("rowValues", rowValues(record));

		Map<String, Object> option = new LinkedHashMap<>();
		option.put("optionId", String.valueOf(payload.get("selectionId")));
		option.put("label", label);
		option.put("value", String.valueOf(payload.get("displayText")));
		option.put("summary", summary);
		option.put("payload", payload);
		return option;
	}

	private String createSelectionToken(String resourceKey, Map<String, Object> record,
			Map<String, Object> tokenContext) {
		cleanupExpiredSelectionTokens();
		String token;
		do {
			token = "sel_" + randomToken(32);
		}
		while (selectionTokens.containsKey(token));
		selectionTokens.put(token, new SelectionEntry(System.currentTimeMillis() + SELECTION_TOKEN_TTL_MILLIS,
				binding(resourceKey, tokenContext), rawData(record)));
		return token;
	}

	private String randomToken(int length) {
		char[] chars = new char[length];
		for (int i = 0; i < length; i++) {
			chars[i] = TOKEN_ALPHABET[secureRandom.nextInt(TOKEN_ALPHABET.length)];
		}
		return new String(chars);
	}

	private void cleanupExpiredSelectionTokens() {
		long now = System.currentTimeMillis();
		if (selectionTokens.size() < 1024) {
			return;
		}
		selectionTokens.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
	}

	private List<Map<String, Object>> rowFields(List<Map<String, Object>> records) {
		List<Map<String, Object>> fields = new ArrayList<>();
		fields.add(Map.of("fieldCode", "record", "label", "记录"));
		Set<String> labels = new LinkedHashSet<>();
		for (Map<String, Object> record : records) {
			for (Map<String, Object> field : displayFields(record)) {
				String label = stringValue(field.get("label"));
				if (StringUtils.hasText(label)) {
					labels.add(label.trim());
				}
				if (labels.size() >= MAX_ROW_FIELDS - 1) {
					break;
				}
			}
			if (labels.size() >= MAX_ROW_FIELDS - 1) {
				break;
			}
		}
		int index = 1;
		for (String label : labels) {
			fields.add(Map.of("fieldCode", "field" + index, "label", label));
			index++;
		}
		return fields;
	}

	private Map<String, Object> rowValues(Map<String, Object> record) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("record", firstText(stringValue(record.get("label")), stringValue(record.get("value")), "候选记录"));
		int index = 1;
		for (Map<String, Object> field : displayFields(record)) {
			values.put("field" + index, stringValue(field.get("value")));
			index++;
			if (index > MAX_ROW_FIELDS) {
				break;
			}
		}
		return values;
	}

	private String selectionText(String label, String summary, Map<String, Object> record) {
		String fields = displaySummary(record);
		String detail = firstText(fields, summary);
		if (!StringUtils.hasText(detail) || detail.equals(label)) {
			return "我选择：" + label;
		}
		return "我选择：" + label + "（" + detail + "）";
	}

	private String displaySummary(Map<String, Object> record) {
		List<String> parts = new ArrayList<>();
		for (Map<String, Object> field : displayFields(record)) {
			String label = stringValue(field.get("label"));
			String value = stringValue(field.get("value"));
			if (StringUtils.hasText(label) && StringUtils.hasText(value)) {
				parts.add(label.trim() + "：" + value.trim());
			}
			if (parts.size() >= 3) {
				break;
			}
		}
		return String.join("，", parts);
	}

	private List<Map<String, Object>> displayFields(Map<String, Object> record) {
		Object fields = record == null ? null : record.get("displayFields");
		if (!(fields instanceof Collection<?> collection)) {
			return List.of();
		}
		return collection.stream()
			.filter(Map.class::isInstance)
			.map(item -> copyStringKeyMap((Map<?, ?>) item))
			.filter(item -> StringUtils.hasText(stringValue(item.get("label")))
					&& StringUtils.hasText(stringValue(item.get("value"))))
			.limit(MAX_ROW_FIELDS - 1)
			.toList();
	}

	private Map<String, Object> rawData(Map<String, Object> record) {
		Object rawData = record == null ? null : record.get("rawData");
		if (!(rawData instanceof Map<?, ?> rawMap)) {
			return Map.of();
		}
		return copyStringKeyMap(rawMap);
	}

	private Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
		Map<String, Object> target = new LinkedHashMap<>();
		source.forEach((key, value) -> {
			if (key != null) {
				target.put(String.valueOf(key), value);
			}
		});
		return target;
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

	private String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private SelectionBinding binding(String resourceKey, Map<String, Object> tokenContext) {
		Map<String, Object> context = tokenContext == null ? Map.of() : tokenContext;
		return new SelectionBinding(firstText(stringValue(context.get("tenantId")),
				stringValue(context.get(AgentRequestSnapshotSupport.TENANT_ID))),
				firstText(stringValue(context.get("agentId")), stringValue(context.get("agent_id"))),
				firstText(stringValue(context.get("sessionId")), stringValue(context.get("threadId")),
						stringValue(context.get("session_id"))),
				firstText(stringValue(context.get("skillCode")), stringValue(context.get("sourceCode"))),
				firstText(stringValue(context.get("skillVersionId"))),
				firstText(resourceKey, stringValue(context.get("resourceKey"))));
	}

	private record SelectionEntry(long expiresAt, SelectionBinding binding, Map<String, Object> rawData) {

		private boolean expired() {
			return expiresAt <= System.currentTimeMillis();
		}

	}

	private record SelectionBinding(String tenantId, String agentId, String sessionId, String skillCode,
			String skillVersionId, String resourceKey) {

		private boolean matches(SelectionBinding other) {
			return matches(tenantId, other == null ? null : other.tenantId())
					&& matches(agentId, other == null ? null : other.agentId())
					&& matches(sessionId, other == null ? null : other.sessionId())
					&& matches(skillCode, other == null ? null : other.skillCode())
					&& matches(skillVersionId, other == null ? null : other.skillVersionId())
					&& matches(resourceKey, other == null ? null : other.resourceKey());
		}

		private boolean matches(String expected, String actual) {
			return !StringUtils.hasText(expected) || (StringUtils.hasText(actual) && expected.equals(actual));
		}

	}

}
