/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将文本通道的确定性输入转换为现有 FLOW 操作。该类不调用模型，也不复制 Resolver 查询逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowTextInteractionResolver {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final List<String> DEFAULT_MATCH_PATHS = List.of("/label", "/demandNo", "/companyName",
			"/companyCode", "/oneProjectName", "/oneProjectCode", "/twoProjectName", "/twoProjectCode",
			"/productName", "/productNo", "/erpCode", "/siteName", "/siteCode", "/fullAddress");

	private final ObjectMapper objectMapper;

	/**
	 * 将文本输入解析为等待态对应的 FLOW 操作：先匹配取消确认，再匹配配置的 CANCEL 动作，
	 * 最后按等待 action 类型分发；无法确定语义时保持 query 原样交回引擎处理。
	 */
	public void resolve(AgentRequest request, DataAgentFlowInstance instance) {
		if (request == null || instance == null || request.getFlowAction() != null
				|| !request.supportsTextCommands() || !StringUtils.hasText(request.getQuery())) {
			return;
		}
		Map<String, Object> waiting = readMap(instance.getWaitingPayload());
		String action = text(waiting.get("action")).trim().toUpperCase(Locale.ROOT);
		if (!StringUtils.hasText(action)) {
			return;
		}
		request.setFlowInstanceId(String.valueOf(instance.getId()));
		String query = normalizeCommand(request.getQuery());
		if ("CANCEL_CONFIRM".equals(action)) {
			resolveCancelConfirm(request, waiting, query);
			return;
		}
		if (applyConfiguredAction(request, waiting, query, List.of("CANCEL"))) {
			return;
		}
		if ("SELECT".equals(action)) {
			resolveSelect(request, instance, waiting, query);
			return;
		}
		if ("REVIEW".equals(action)) {
			resolveReview(request, waiting, query);
			return;
		}
		if ("CONFIRM".equals(action)) {
			resolveConfirm(request, waiting, query);
		}
	}

	/** 取消确认态：只接受"确认取消/继续"两种语义，其余输入按无操作处理。 */
	private void resolveCancelConfirm(AgentRequest request, Map<String, Object> waiting, String query) {
		Map<String, Object> cancelPolicy = map(waiting.get("cancelPolicy"));
		if (matches(query, text(cancelPolicy.get("confirmLabel")), "确认取消", "确认取消下单", "确认")) {
			setAction(request, "confirm-cancel", "CONFIRM_CANCEL", true);
		}
		else if (matches(query, text(cancelPolicy.get("keepLabel")), "继续", "继续当前流程", "返回流程")) {
			setAction(request, "keep-flow", "KEEP_FLOW", false);
		}
		else {
			markNoop(request);
		}
	}

	/** 选择态：依次尝试序号选择、精确匹配、跳过语义、配置动作；均未命中且开启文本搜索时转搜索意图。 */
	private void resolveSelect(AgentRequest request, DataAgentFlowInstance instance, Map<String, Object> waiting,
			String query) {
		Integer index = parseIndex(request.getQuery());
		if (index != null) {
			Map<String, Object> selected = findIndexedOption(waiting.get("options"), index);
			if (!selected.isEmpty()) {
				setAction(request, "select-option", "SELECT", selected.get("value"));
			}
			else {
				markNoop(request);
			}
			return;
		}
		List<String> matchPaths = strings(map(waiting.get("textSearch")).get("matchPaths"));
		Map<String, Object> selected = findExactOption(waiting.get("options"), request.getQuery(), matchPaths);
		if (!selected.isEmpty()) {
			setAction(request, "select-option", "SELECT", selected.get("value"));
			return;
		}
		if (isSkip(query, waiting)) {
			setAction(request, "skip-selection", "SKIP", true);
			return;
		}
		// 配置了 fallbackNode 的选择卡带 FALLBACK（重选）动作：键入其 label（如"重新选择"）即可触发。
		if (applyConfiguredAction(request, waiting, query, List.of("SKIP", "FALLBACK"))) {
			return;
		}
		if (applyCancelAction(request, waiting, query)) {
			return;
		}
		Map<String, Object> textSearch = map(waiting.get("textSearch"));
		if (Boolean.TRUE.equals(textSearch.get("enabled"))
				&& StringUtils.hasText(text(textSearch.get("resolverNode")))) {
			String keyword = request.getQuery().trim();
			request.setFlowTextSearchIntent(new FlowTextSearchIntent(
					instance.getCurrentNodeId(), text(textSearch.get("resolverNode")), text(waiting.get("targetPath")),
					keyword, firstText(text(textSearch.get("argumentName")), "keyword"), false));
		}
		else {
			markNoop(request);
		}
	}

	/** 复核态：优先配置动作，再匹配"继续提交/清除历史参考"内置语义；未命中的文本交回引擎抽取。 */
	private void resolveReview(AgentRequest request, Map<String, Object> waiting, String query) {
		if (applyConfiguredAction(request, waiting, query, List.of("SUBMIT", "CLEAR_REFERENCE"))) {
			return;
		}
		if (Boolean.TRUE.equals(waiting.get("blocking"))) {
			return;
		}
		if (matches(query, "继续", "提交", "信息无误", "使用当前信息继续下单")) {
			setAction(request, "review-submit", "SUBMIT", true);
		}
		else if (matches(query, "清除历史参考", "清除历史", "不用历史参考")) {
			setAction(request, "clear-reference", "CLEAR_REFERENCE", null);
		}
	}

	/** 确认态：优先配置动作与内置确认/修改语义；其余非空文本按"直改内容"转 EDIT。 */
	private void resolveConfirm(AgentRequest request, Map<String, Object> waiting, String query) {
		if (applyConfiguredAction(request, waiting, query, List.of("CONFIRM", "EDIT"))) {
			return;
		}
		if (matches(query, "确认提交", "确认下单", "提交订单", "确认")) {
			setAction(request, "confirm-submit", "CONFIRM", true);
		}
		else if (matches(query, "返回修改", "修改信息", "继续修改")) {
			setAction(request, "edit-flow", "EDIT", null);
		}
		else if (StringUtils.hasText(request.getQuery())) {
			request.setFlowTextDirectEdit(true);
			setAction(request, "edit-flow", "EDIT", null);
		}
	}

	private boolean applyConfiguredAction(AgentRequest request, Map<String, Object> waiting, String query,
			List<String> allowedTypes) {
		Map<String, Object> matched = Map.of();
		for (Object item : list(waiting.get("uiActions"))) {
			Map<String, Object> action = map(item);
			String type = text(action.get("type")).trim().toUpperCase(Locale.ROOT);
			if (!allowedTypes.contains(type) || !query.equals(normalizeCommand(text(action.get("label"))))) {
				continue;
			}
			if (!matched.isEmpty()) {
				return false;
			}
			matched = action;
		}
		if (matched.isEmpty()) {
			return false;
		}
		setAction(request, firstText(text(matched.get("actionId")), "text-command"),
				text(matched.get("type")), matched.get("value"), map(matched.get("payload")));
		return true;
	}

	/**
	 * 等待卡真实提供 CANCEL 动作时，键入内置取消语义（如"取消/取消流程"）直接识别为取消，
	 * 与 LLM 抽取的取消识别互补：空选项文本搜索等待态跳过抽取后，取消语义不再依赖模型。
	 */
	private boolean applyCancelAction(AgentRequest request, Map<String, Object> waiting, String query) {
		Map<String, Object> cancelAction = Map.of();
		for (Object item : list(waiting.get("uiActions"))) {
			Map<String, Object> action = map(item);
			if ("CANCEL".equals(text(action.get("type")).trim().toUpperCase(Locale.ROOT))) {
				cancelAction = action;
				break;
			}
		}
		if (cancelAction.isEmpty() || !matches(query, "取消", "取消流程", "终止")) {
			return false;
		}
		setAction(request, firstText(text(cancelAction.get("actionId")), "cancel-flow"),
				"CANCEL", cancelAction.get("value"), map(cancelAction.get("payload")));
		return true;
	}

	private Map<String, Object> findExactOption(Object options, String query, List<String> configuredMatchPaths) {
		String normalizedQuery = normalizeCommand(query);
		if (!StringUtils.hasText(normalizedQuery) || !(options instanceof Iterable<?> iterable)) {
			return Map.of();
		}
		List<Map<String, Object>> matches = new ArrayList<>();
		for (Object option : iterable) {
			Map<String, Object> candidate = map(option);
			if (candidate.isEmpty()) {
				continue;
			}
			if (matches(normalizedQuery, normalizeCommand(text(candidate.get("label"))))
					|| scalarInRawData(candidate.get("rawData"), normalizedQuery, configuredMatchPaths)) {
				matches.add(candidate);
			}
		}
		return matches.size() == 1 ? matches.get(0) : Map.of();
	}

	private boolean scalarInRawData(Object rawData, String query, List<String> configuredMatchPaths) {
		Map<String, Object> raw = map(rawData);
		List<String> matchPaths = configuredMatchPaths.isEmpty() ? DEFAULT_MATCH_PATHS : configuredMatchPaths;
		for (String path : matchPaths) {
			Object value = readPointer(raw, path);
			if (value != null && query.equals(normalizeCommand(String.valueOf(value)))) {
				return true;
			}
		}
		return false;
	}

	private Object readPointer(Map<String, Object> source, String pointer) {
		Object current = source;
		for (String segment : text(pointer).split("/")) {
			if (segment.isEmpty()) {
				continue;
			}
			if (!(current instanceof Map<?, ?> currentMap)) {
				return null;
			}
			current = currentMap.get(segment.replace("~1", "/").replace("~0", "~"));
		}
		return current;
	}

	private Map<String, Object> findIndexedOption(Object options, int index) {
		if (index < 1 || !(options instanceof Iterable<?> iterable)) {
			return Map.of();
		}
		int current = 1;
		for (Object option : iterable) {
			if (current++ == index) {
				return map(option);
			}
		}
		return Map.of();
	}

	private Integer parseIndex(String value) {
		String normalized = normalizeCommand(value).replace("第", "").replace("项", "").replace("个", "")
				.replace("选择", "");
		try {
			return normalized.matches("\\d{1,3}") ? Integer.valueOf(normalized) : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private boolean isSkip(String query, Map<String, Object> waiting) {
		if (!Boolean.TRUE.equals(waiting.get("allowSkip"))) {
			return false;
		}
		for (Object command : list(waiting.get("skipCommands"))) {
			if (query.equals(normalizeCommand(text(command)))) {
				return true;
			}
		}
		return false;
	}

	private void setAction(AgentRequest request, String actionId, String type, Object value) {
		setAction(request, actionId, type, value, Map.of());
	}

	private void setAction(AgentRequest request, String actionId, String type, Object value,
			Map<String, Object> payload) {
		request.setFlowAction(new FlowAction(actionId, type, value, payload));
	}

	private void markNoop(AgentRequest request) {
		request.setFlowTextNoop(true);
	}

	private boolean matches(String query, String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value) && query.equals(normalizeCommand(value))) {
				return true;
			}
		}
		return false;
	}

	private String normalizeCommand(String value) {
		return text(value).trim().toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？,.!?]", "");
	}

	private String firstText(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private List<?> list(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	private List<String> strings(Object value) {
		if (!(value instanceof Iterable<?> iterable)) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		for (Object item : iterable) {
			if (StringUtils.hasText(text(item))) {
				result.add(text(item));
			}
		}
		return List.copyOf(result);
	}

	private String text(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private Map<String, Object> readMap(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(value, MAP_TYPE);
		}
		catch (Exception ex) {
			// 空 Map 会让本轮交互当作"没有待填字段"，与实际状态不符
			log.warn("Unable to parse the persisted FLOW interaction payload, treating it as empty. length={}",
					value.length(), ex);
			return Map.of();
		}
	}

}
