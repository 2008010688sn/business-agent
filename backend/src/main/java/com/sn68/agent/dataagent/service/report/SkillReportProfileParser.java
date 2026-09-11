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
package com.sn68.agent.dataagent.service.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 从已命中的 Skill 版本中提取受控的报告规范片段。
 */
public final class SkillReportProfileParser {

	private static final String REPORT_SECTION = "报告规范";

	private static final int MAX_ITEMS_PER_SECTION = 8;

	private static final int MAX_ITEM_CHARS = 120;

	private static final Pattern LIST_PREFIX = Pattern.compile("^(?:[-*+]\\s+|\\d+[.、)]\\s*)");

	private SkillReportProfileParser() {
	}

	public static SkillReportProfile parse(String markdown) {
		if (!StringUtils.hasText(markdown)) {
			return null;
		}
		Map<String, List<String>> sections = new LinkedHashMap<>();
		boolean inReportSection = false;
		String currentSection = null;
		for (String rawLine : markdown.split("\\R")) {
			String line = rawLine.trim();
			if (line.startsWith("## ") && !line.startsWith("### ")) {
				String title = line.substring(3).trim();
				if (inReportSection && !REPORT_SECTION.equals(title)) {
					break;
				}
				inReportSection = REPORT_SECTION.equals(title);
				currentSection = null;
				continue;
			}
			if (!inReportSection) {
				continue;
			}
			if (line.startsWith("### ")) {
				currentSection = line.substring(4).trim();
				sections.computeIfAbsent(currentSection, ignored -> new ArrayList<>());
				continue;
			}
			if (!StringUtils.hasText(currentSection) || !StringUtils.hasText(line)) {
				continue;
			}
			List<String> values = sections.get(currentSection);
			if (values.size() >= MAX_ITEMS_PER_SECTION) {
				continue;
			}
			String value = LIST_PREFIX.matcher(line).replaceFirst("").replace("`", "").trim();
			if (StringUtils.hasText(value)) {
				values.add(value.substring(0, Math.min(value.length(), MAX_ITEM_CHARS)));
			}
		}
		SkillReportProfile profile = SkillReportProfile.builder()
			.metrics(values(sections, "关注指标"))
			.dimensions(values(sections, "推荐维度"))
			.risks(values(sections, "重点风险"))
			.actionDirections(values(sections, "行动建议方向"))
			.subSections(values(sections, "推荐子章节"))
			.terms(values(sections, "领域术语"))
			.build();
		return profile.isEmpty() ? null : profile;
	}

	private static List<String> values(Map<String, List<String>> sections, String name) {
		return List.copyOf(sections.getOrDefault(name, List.of()));
	}

}
