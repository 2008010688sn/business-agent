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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 校验 AI 生成的分析报告正文中的业务数值是否都能对应平台确定性计算的白名单结果，拦截模型编造的数字。
 *
 * <p>
 * 无状态纯 Java 工具，不依赖 Spring；白名单由调用方基于权威快照构建，日志与兜底策略也由调用方负责。
 */
public final class ReportNarrativeValidator {

	private static final int MAX_TOLERATED_UNKNOWN_VALUES = 3;

	/** 数值近似匹配的相对容差，覆盖模型对均值/占比的四舍五入（如 1234.5678 写作 1234.57）。 */
	private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("0.005");

	/** 小数值的绝对容差下限，避免相对容差在小数上过严。 */
	private static final BigDecimal ABSOLUTE_TOLERANCE = new BigDecimal("0.01");

	private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

	private static final BigDecimal HUNDRED_MILLION = new BigDecimal("100000000");

	private static final Pattern NUMBER_TOKEN = Pattern.compile("-?[0-9][0-9,]*(?:\\.[0-9]+)?(?:%|万|亿)?");

	private static final Pattern DATE_SEQUENCE = Pattern
		.compile("\\d{4}-\\d{1,2}(?:-\\d{1,2})?|\\d{4}/\\d{1,2}(?:/\\d{1,2})?");

	private static final Pattern FOUR_DIGIT_YEAR = Pattern.compile("\\d{4}");

	ReportNarrativeValidator() {
	}

	/**
	 * 校验结果。
	 *
	 * @param passed 未命中白名单的不同数值是否不超过容忍阈值
	 * @param unknownValues 未命中的原始数值 token，按首次出现顺序返回，供调用方日志观测
	 */
	public record ValidationResult(boolean passed, List<String> unknownValues) {
	}

	/**
	 * Drop root causes that cite no evidence. Zero-citation claims never reach the report contract.
	 */
	public List<AnalysisRootCause> retainCitedRootCauses(List<AnalysisRootCause> rootCauses) {
		if (rootCauses == null || rootCauses.isEmpty()) {
			return List.of();
		}
		return rootCauses.stream().filter(AnalysisRootCause::hasEvidence).toList();
	}

	/**
	 * Business numbers in narrative text. Date sequences ({@code 2025-01}) and standalone years are
	 * labels, not required evidence values.
	 */
	public List<BigDecimal> businessNumbersIn(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		List<BigDecimal> values = new ArrayList<>();
		Matcher matcher = NUMBER_TOKEN.matcher(maskDateSequences(text));
		while (matcher.find()) {
			ParsedNumber parsed = parse(matcher.group());
			if (parsed == null || isStandaloneYear(parsed)) {
				continue;
			}
			values.add(parsed.value());
		}
		return List.copyOf(values);
	}

	/**
	 * Keep findings whose numbers all appear in evidence. Findings without numbers are kept as-is.
	 */
	public List<AnalysisFinding> retainEvidencedFindings(List<AnalysisFinding> findings,
			Set<BigDecimal> evidenceNumbers) {
		if (findings == null || findings.isEmpty()) {
			return List.of();
		}
		List<BigDecimal> allowed = allowedValues(evidenceNumbers);
		List<AnalysisFinding> retained = new ArrayList<>();
		for (AnalysisFinding finding : findings) {
			if (finding == null) {
				continue;
			}
			if (finding.numbers().isEmpty()
					|| finding.numbers().stream().allMatch(value -> matchesAnyScale(value, allowed))) {
				retained.add(finding);
			}
		}
		return List.copyOf(retained);
	}

	public ValidationResult validate(String report, Set<BigDecimal> allowedNumbers) {
		if (report == null || report.isBlank()) {
			return new ValidationResult(true, List.of());
		}
		List<BigDecimal> allowed = allowedValues(allowedNumbers);
		Map<String, String> unknownByKey = new LinkedHashMap<>();
		Matcher matcher = NUMBER_TOKEN.matcher(maskDateSequences(report));
		while (matcher.find()) {
			String token = matcher.group();
			ParsedNumber parsed = parse(token);
			if (parsed == null || isStandaloneYear(parsed)) {
				continue;
			}
			if (matchesAnyScale(parsed.value(), allowed)) {
				continue;
			}
			unknownByKey.putIfAbsent(keyOf(parsed.value()), token);
		}
		List<String> unknownValues = List.copyOf(unknownByKey.values());
		boolean passed = allowed.isEmpty() ? unknownValues.isEmpty()
				: unknownValues.size() <= MAX_TOLERATED_UNKNOWN_VALUES;
		return new ValidationResult(passed, unknownValues);
	}

	private List<BigDecimal> allowedValues(Set<BigDecimal> allowedNumbers) {
		if (allowedNumbers == null || allowedNumbers.isEmpty()) {
			return List.of();
		}
		return allowedNumbers.stream().filter(Objects::nonNull).toList();
	}

	private String maskDateSequences(String report) {
		return DATE_SEQUENCE.matcher(report).replaceAll(" ");
	}

	private ParsedNumber parse(String token) {
		String body = token;
		BigDecimal factor = BigDecimal.ONE;
		boolean suffixed = false;
		if (body.endsWith("%")) {
			suffixed = true;
			body = body.substring(0, body.length() - 1);
		}
		else if (body.endsWith("亿")) {
			suffixed = true;
			factor = HUNDRED_MILLION;
			body = body.substring(0, body.length() - 1);
		}
		else if (body.endsWith("万")) {
			suffixed = true;
			factor = TEN_THOUSAND;
			body = body.substring(0, body.length() - 1);
		}
		try {
			return new ParsedNumber(new BigDecimal(body.replace(",", "")).multiply(factor), suffixed);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private boolean isStandaloneYear(ParsedNumber parsed) {
		if (parsed.suffixed()) {
			return false;
		}
		String plain = parsed.value().toPlainString();
		if (!FOUR_DIGIT_YEAR.matcher(plain).matches()) {
			return false;
		}
		int year = Integer.parseInt(plain);
		return year >= 1900 && year <= 2100;
	}

	private boolean matchesAnyScale(BigDecimal value, List<BigDecimal> allowed) {
		return matches(value, allowed) || matches(value.movePointRight(2), allowed)
				|| matches(value.movePointLeft(2), allowed);
	}

	private boolean matches(BigDecimal value, List<BigDecimal> allowed) {
		for (BigDecimal candidate : allowed) {
			if (keyOf(candidate).equals(keyOf(value))) {
				return true;
			}
			BigDecimal tolerance = candidate.abs().multiply(RELATIVE_TOLERANCE).max(ABSOLUTE_TOLERANCE);
			if (value.subtract(candidate).abs().compareTo(tolerance) <= 0) {
				return true;
			}
		}
		return false;
	}

	private String keyOf(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private record ParsedNumber(BigDecimal value, boolean suffixed) {
	}

}
