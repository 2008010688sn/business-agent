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

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 报告意图识别器：判断当前问答是否应当生成分析报告。
 */
@Component
public class ReportIntentDetector {

	private static final List<String> EXCLUDED_TERMS = List.of("报告编号", "报告号", "报表有多少", "报表数量", "报告数量",
			"报表编号");

	private static final List<String> EXPLICIT_REPORT_TERMS = List.of("分析报告", "形成报告", "生成报告", "输出报告",
			"出一份报告", "写一份报告", "下载报告", "汇报材料");

	private static final List<String> PERIOD_REPORT_TERMS = List.of("月报", "周报", "日报");

	private static final List<String> BUSINESS_TABLE_TERMS = List.of("月报表", "周报表", "日报表");

	public boolean shouldGenerateReport(String query, String responseMode) {
		if ("report".equalsIgnoreCase(StringUtils.trimWhitespace(responseMode))) {
			return true;
		}
		if (!StringUtils.hasText(query)) {
			return false;
		}
		String normalized = query.trim().toLowerCase(Locale.ROOT);
		for (String explicitTerm : EXPLICIT_REPORT_TERMS) {
			if (normalized.contains(explicitTerm.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		if (containsAny(normalized, EXCLUDED_TERMS) || containsAny(normalized, BUSINESS_TABLE_TERMS)) {
			return false;
		}
		if (containsAny(normalized, PERIOD_REPORT_TERMS)) {
			return true;
		}
		return false;
	}

	private boolean containsAny(String text, List<String> terms) {
		for (String term : terms) {
			if (text.contains(term.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

}
