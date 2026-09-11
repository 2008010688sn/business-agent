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
package com.sn68.agent.dataagent.linking;

import com.sn68.agent.dataagent.agentscope.dto.GroundedKey;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 从问句抽出 URL 与未命名键。兼容 query、path 末段、hash 路由、Markdown 与尾标点。
 */
public final class LinkKeyExtractor {

	static final int MAX_KEYS_PER_URL = 3;

	static final int MAX_KEYS_PER_TURN = 6;

	static final int MAX_TOKEN_LENGTH = 64;

	static final int MAX_URLS = 8;

	private static final Pattern MARKDOWN_URL = Pattern.compile("\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern ANGLE_URL = Pattern.compile("<(https?://[^>\\s]+)>", Pattern.CASE_INSENSITIVE);

	private static final Pattern BARE_URL = Pattern.compile("(https?://[^\\s<>\"'`]+)", Pattern.CASE_INSENSITIVE);

	private static final String TRAILING_PUNCT = ")]},。；、\"'`」』）】>";

	private static final Set<String> DROPPED_PARAMS = Set.of("page", "tab", "size");

	private static final Pattern TOKEN_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

	private LinkKeyExtractor() {
	}

	public static List<String> extractUrls(String... texts) {
		LinkedHashSet<String> urls = new LinkedHashSet<>();
		if (texts == null) {
			return List.of();
		}
		for (String text : texts) {
			collectUrls(text, urls);
			if (urls.size() >= MAX_URLS) {
				break;
			}
		}
		List<String> result = new ArrayList<>();
		for (String url : urls) {
			result.add(url);
			if (result.size() >= MAX_URLS) {
				break;
			}
		}
		return List.copyOf(result);
	}

	public static List<GroundedKey> extractKeys(URI uri, String trust) {
		if (uri == null || !StringUtils.hasText(trust)) {
			return List.of();
		}
		List<GroundedKey> keys = new ArrayList<>();
		addQueryKeys(keys, uri.getRawQuery(), trust);
		String fragment = uri.getRawFragment();
		String fragmentPath = fragment;
		if (StringUtils.hasText(fragment) && fragment.contains("?")) {
			int queryAt = fragment.indexOf('?');
			fragmentPath = fragment.substring(0, queryAt);
			addQueryKeys(keys, fragment.substring(queryAt + 1), trust);
		}
		addPathKey(keys, lastSegment(uri.getPath()), trust);
		addPathKey(keys, lastSegment(fragmentPath), trust);
		return rankAndCap(keys);
	}

	public static GroundedKey pageContextKey(String name, String value) {
		String safeName = sanitizeToken(name);
		String safeValue = sanitizeToken(value);
		if (!StringUtils.hasText(safeName) || !StringUtils.hasText(safeValue)) {
			return null;
		}
		return new GroundedKey(safeName, safeValue, GroundedKey.TRUST_PAGE_CONTEXT);
	}

	private static void collectUrls(String text, Set<String> urls) {
		if (!StringUtils.hasText(text) || urls.size() >= MAX_URLS) {
			return;
		}
		addMatches(MARKDOWN_URL, text, urls);
		addMatches(ANGLE_URL, text, urls);
		addMatches(BARE_URL, text, urls);
	}

	private static void addMatches(Pattern pattern, String text, Set<String> urls) {
		Matcher matcher = pattern.matcher(text);
		while (matcher.find() && urls.size() < MAX_URLS) {
			String cleaned = stripTrailingPunct(matcher.group(1));
			if (StringUtils.hasText(cleaned) && cleaned.regionMatches(true, 0, "http", 0, 4)) {
				urls.add(cleaned);
			}
		}
	}

	static String stripTrailingPunct(String raw) {
		if (!StringUtils.hasText(raw)) {
			return raw;
		}
		int end = raw.length();
		while (end > 0 && TRAILING_PUNCT.indexOf(raw.charAt(end - 1)) >= 0) {
			end--;
		}
		return end == raw.length() ? raw : raw.substring(0, end);
	}

	private static void addQueryKeys(List<GroundedKey> keys, String rawQuery, String trust) {
		if (!StringUtils.hasText(rawQuery)) {
			return;
		}
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String name = decode(pair.substring(0, eq));
			String value = decode(pair.substring(eq + 1));
			if (isDroppedParam(name) || !isHintKeyName(name)) {
				continue;
			}
			String safeName = sanitizeToken(name);
			String safeValue = sanitizeToken(value);
			if (!StringUtils.hasText(safeName) || !StringUtils.hasText(safeValue)) {
				continue;
			}
			keys.add(new GroundedKey(safeName, safeValue, trust));
		}
	}

	private static void addPathKey(List<GroundedKey> keys, String segment, String trust) {
		String safe = sanitizeToken(segment);
		if (!StringUtils.hasText(safe) || !containsDigit(safe)) {
			return;
		}
		String name = looksLikeSnowflake(safe) ? "id" : "no";
		keys.add(new GroundedKey(name, safe, trust));
	}

	private static String lastSegment(String path) {
		if (!StringUtils.hasText(path)) {
			return null;
		}
		String trimmed = path;
		int hash = trimmed.indexOf('#');
		if (hash >= 0) {
			trimmed = trimmed.substring(hash + 1);
		}
		if (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1);
		}
		if (!StringUtils.hasText(trimmed)) {
			return null;
		}
		int slash = trimmed.lastIndexOf('/');
		String segment = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
		int queryAt = segment.indexOf('?');
		return queryAt >= 0 ? segment.substring(0, queryAt) : segment;
	}

	private static List<GroundedKey> rankAndCap(List<GroundedKey> keys) {
		if (keys.isEmpty()) {
			return List.of();
		}
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<GroundedKey> unique = new ArrayList<>();
		keys.sort(Comparator.comparingInt(LinkKeyExtractor::rank));
		for (GroundedKey key : keys) {
			String identity = key.getName() + "=" + key.getValue();
			if (seen.add(identity)) {
				unique.add(key);
			}
			if (unique.size() >= MAX_KEYS_PER_URL) {
				break;
			}
		}
		return List.copyOf(unique);
	}

	private static int rank(GroundedKey key) {
		if (key == null || !StringUtils.hasText(key.getValue())) {
			return 9;
		}
		if (looksLikeSnowflake(key.getValue())) {
			return 0;
		}
		String name = key.getName() == null ? "" : key.getName().toLowerCase(Locale.ROOT);
		if (name.endsWith("code") || name.endsWith("no")) {
			return 1;
		}
		return 2;
	}

	static String sanitizeToken(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String sanitized = TOKEN_CHARS.matcher(value.trim()).replaceAll("");
		if (!StringUtils.hasText(sanitized)) {
			return null;
		}
		return sanitized.length() <= MAX_TOKEN_LENGTH ? sanitized : sanitized.substring(0, MAX_TOKEN_LENGTH);
	}

	private static boolean isDroppedParam(String name) {
		return StringUtils.hasText(name) && DROPPED_PARAMS.contains(name.trim().toLowerCase(Locale.ROOT));
	}

	private static boolean isHintKeyName(String name) {
		if (!StringUtils.hasText(name)) {
			return false;
		}
		String trimmed = name.trim();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		return "id".equals(lower) || "code".equals(lower) || "no".equals(lower) || lower.endsWith("id")
				|| lower.endsWith("no") || lower.endsWith("code");
	}

	private static boolean looksLikeSnowflake(String value) {
		return StringUtils.hasText(value) && value.length() >= 16 && value.chars().allMatch(Character::isDigit);
	}

	private static boolean containsDigit(String value) {
		return StringUtils.hasText(value) && value.chars().anyMatch(Character::isDigit);
	}

	private static String decode(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			return value;
		}
	}

}
