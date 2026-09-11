/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 把用户当前这句话对上已经列出的候选项。只打本轮 options / rawData，不写业务短语名单。
 */
public final class FlowListedOptionSupport {

	private static final List<String> DEFAULT_MATCH_PATHS = List.of("/label", "/demandNo", "/companyName",
			"/companyCode", "/oneProjectName", "/oneProjectCode", "/twoProjectName", "/twoProjectCode",
			"/productName", "/productNo", "/erpCode", "/siteName", "/siteCode", "/fullAddress");

	private static final int MIN_CONTAINED_TOKEN = 3;

	private FlowListedOptionSupport() {
	}

	public static Map<String, Object> matchUnique(Object options, String query) {
		String normalizedQuery = normalize(query);
		if (!StringUtils.hasText(normalizedQuery) || !(options instanceof Iterable<?> iterable)) {
			return Map.of();
		}
		List<Map<String, Object>> exact = new ArrayList<>();
		List<Map<String, Object>> contained = new ArrayList<>();
		for (Object item : iterable) {
			if (!(item instanceof Map<?, ?> map)) {
				continue;
			}
			Map<String, Object> candidate = cast(map);
			if (candidate.isEmpty()) {
				continue;
			}
			List<String> tokens = tokens(candidate);
			if (tokens.stream().anyMatch(normalizedQuery::equals)) {
				exact.add(candidate);
			}
			else if (tokens.stream().anyMatch(token -> containedMatch(normalizedQuery, token))) {
				contained.add(candidate);
			}
		}
		if (exact.size() == 1) {
			return exact.get(0);
		}
		if (exact.isEmpty() && contained.size() == 1) {
			return contained.get(0);
		}
		return Map.of();
	}

	public static Map<String, Object> matchSole(Object options) {
		if (!(options instanceof Iterable<?> iterable)) {
			return Map.of();
		}
		Map<String, Object> sole = Map.of();
		int count = 0;
		for (Object item : iterable) {
			if (!(item instanceof Map<?, ?> map)) {
				continue;
			}
			Map<String, Object> candidate = cast(map);
			if (candidate.isEmpty()) {
				continue;
			}
			count++;
			if (count > 1) {
				return Map.of();
			}
			sole = candidate;
		}
		return count == 1 ? sole : Map.of();
	}

	private static List<String> tokens(Map<String, Object> candidate) {
		List<String> tokens = new ArrayList<>();
		addToken(tokens, candidate.get("label"));
		addToken(tokens, candidate.get("value"));
		Object raw = candidate.get("rawData");
		if (raw instanceof Map<?, ?> rawMap) {
			Map<String, Object> data = cast(rawMap);
			for (String path : DEFAULT_MATCH_PATHS) {
				addToken(tokens, readPointer(data, path));
			}
		}
		return tokens;
	}

	private static void addToken(List<String> tokens, Object value) {
		String normalized = normalize(value == null ? "" : String.valueOf(value));
		if (StringUtils.hasText(normalized) && !tokens.contains(normalized)) {
			tokens.add(normalized);
		}
	}

	private static boolean containedMatch(String query, String token) {
		if (!StringUtils.hasText(token) || token.length() < MIN_CONTAINED_TOKEN) {
			return false;
		}
		return query.contains(token) || token.contains(query) && query.length() >= MIN_CONTAINED_TOKEN;
	}

	private static String normalize(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？,.!?\\-_/]", "");
	}

	private static Object readPointer(Map<String, Object> source, String pointer) {
		Object current = source;
		for (String segment : pointer.split("/")) {
			if (segment.isEmpty()) {
				continue;
			}
			if (!(current instanceof Map<?, ?> currentMap)) {
				return null;
			}
			current = currentMap.get(segment);
		}
		return current;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Map<?, ?> map) {
		return (Map<String, Object>) map;
	}

}
