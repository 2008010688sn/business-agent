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
package com.sn68.agent.dataagent.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 问句分词：空白切分 + 中文 2/3 字窗口，供语义 linking 与 FIND_TABLES 共用。
 */
public final class QueryTokenUtil {

	private static final int MAX_TOKENS = 32;

	private static final Pattern CJK_RUN = Pattern.compile("[\\p{IsHan}]+");

	private static final Pattern LATIN_RUN = Pattern.compile("[a-zA-Z0-9]+");

	private static final Set<String> STOPWORDS = Set.of("这个", "一下", "情况", "目前", "帮我", "看看", "看下", "以及",
			"这些", "那些", "怎么", "如何", "什么", "哪些", "的", "了", "吗", "呢", "啊", "请", "在", "是", "有",
			"和", "与", "或", "为", "对", "把", "被", "就", "都", "也", "很", "最", "更", "各", "个", "等");

	private QueryTokenUtil() {
	}

	public static List<String> tokenize(String query) {
		String normalized = normalize(query);
		if (!StringUtils.hasText(normalized)) {
			return List.of();
		}
		LinkedHashSet<String> tokens = new LinkedHashSet<>();
		tokens.add(normalized);
		for (String piece : normalized.split("\\s+")) {
			addToken(tokens, piece);
		}
		Matcher cjk = CJK_RUN.matcher(normalized.replace(" ", ""));
		while (cjk.find()) {
			addCjkWindows(tokens, cjk.group());
		}
		Matcher latin = LATIN_RUN.matcher(normalized);
		while (latin.find()) {
			addToken(tokens, latin.group());
		}
		return tokens.stream().limit(MAX_TOKENS).toList();
	}

	public static String normalize(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		return value.trim()
			.toLowerCase(Locale.ROOT)
			.replace('_', ' ')
			.replace('-', ' ')
			.replaceAll("[()\\[\\]{}]", " ")
			.replaceAll("[,，;；/|、]+", " ")
			.replaceAll("\\s+", " ");
	}

	private static void addCjkWindows(Set<String> tokens, String run) {
		if (!StringUtils.hasText(run)) {
			return;
		}
		if (run.length() <= 3) {
			addToken(tokens, run);
			return;
		}
		for (int size = 2; size <= 3; size++) {
			for (int index = 0; index + size <= run.length(); index++) {
				addToken(tokens, run.substring(index, index + size));
			}
		}
	}

	private static void addToken(Set<String> tokens, String token) {
		if (!StringUtils.hasText(token) || STOPWORDS.contains(token)) {
			return;
		}
		tokens.add(token);
	}

}
