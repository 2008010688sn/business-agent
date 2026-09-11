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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportNarrativeValidatorTest {

	private final ReportNarrativeValidator validator = new ReportNarrativeValidator();

	@Test
	void allNarrativeNumbersResolvedFromWhitelistPassValidation() {
		ReportNarrativeValidator.ValidationResult result = validator
			.validate("本月总金额 167,650 元，账单数 55 笔，运费退差 -2040 元。", allowed("167650", "55", "-2040"));

		assertTrue(result.passed());
		assertTrue(result.unknownValues().isEmpty());
	}

	@Test
	void chineseMagnitudeSuffixesAreScaledBeforeComparison() {
		ReportNarrativeValidator.ValidationResult result = validator
			.validate("上半年营收 123.5万元，全年目标 1.2亿元。", allowed("1235000", "120000000"));

		assertTrue(result.passed());
		assertTrue(result.unknownValues().isEmpty());
	}

	@Test
	void percentValuesMatchWhitelistOnEitherScale() {
		assertTrue(validator.validate("达成率 35%。", allowed("0.35")).passed());
		assertTrue(validator.validate("达成率 35%。", allowed("35")).passed());

		ReportNarrativeValidator.ValidationResult miss = validator.validate("达成率 60%。", allowed("0.35"));

		assertTrue(miss.passed());
		assertEquals(List.of("60%"), miss.unknownValues());
	}

	@Test
	void roundingOfDerivedAveragesStaysWithinTolerance() {
		ReportNarrativeValidator.ValidationResult result = validator
			.validate("均值约 1,234.57 元，占比 23.4%。", allowed("1234.5678", "0.234"));

		assertTrue(result.passed());
		assertTrue(result.unknownValues().isEmpty());
	}

	@Test
	void fabricatedNumbersFarFromWhitelistStillFail() {
		ReportNarrativeValidator.ValidationResult result = validator
			.validate("收入 11 元，成本 22 元，利润 33 元，结余 44。", allowed("100"));

		assertFalse(result.passed());
		assertEquals(List.of("11", "22", "33", "44"), result.unknownValues());
	}

	@Test
	void datesAndYearsAreExcludedFromNumberChecking() {
		ReportNarrativeValidator.ValidationResult result = validator
			.validate("统计周期 2025-08-01 至 2025/08/31，报告生成于 2025年。", Set.of());

		assertTrue(result.passed());
		assertTrue(result.unknownValues().isEmpty());
	}

	@Test
	void moreThanThreeDistinctUnknownNumbersFailValidation() {
		ReportNarrativeValidator.ValidationResult result = validator.validate(
				"收入 11 元，成本 22 元，利润 33 元，同比增长 44%，另有笔误 50。", allowed("99"));

		assertFalse(result.passed());
		assertEquals(List.of("11", "22", "33", "44%", "50"), result.unknownValues());
	}

	@Test
	void threeDistinctUnknownNumbersAreToleratedButReported() {
		ReportNarrativeValidator.ValidationResult result = validator.validate("编号片段 7、备注 8、页码 9。", allowed("1"));

		assertTrue(result.passed());
		assertEquals(List.of("7", "8", "9"), result.unknownValues());
	}

	@Test
	void duplicateUnknownNumbersAreCountedOnce() {
		ReportNarrativeValidator.ValidationResult result = validator.validate("重复出现 66 和 66。", allowed("1"));

		assertTrue(result.passed());
		assertEquals(List.of("66"), result.unknownValues());
	}

	@Test
	void rootCausesWithoutEvidenceIdsAreDiscarded() {
		List<AnalysisRootCause> retained = validator.retainCitedRootCauses(List.of(
				new AnalysisRootCause("无引用的推测", List.of(), "low", "missingEvidence"),
				new AnalysisRootCause("空引用", List.of("  "), "low", null),
				new AnalysisRootCause("有证据", List.of("ev-1"), "medium", null)));

		assertEquals(1, retained.size());
		assertEquals("有证据", retained.get(0).claim());
		assertEquals(List.of("ev-1"), retained.get(0).evidenceIds());
	}

	@Test
	void businessNumbersInSkipsDateSequencesAndStandaloneYears() {
		List<BigDecimal> numbers = validator
			.businessNumbersIn("总金额 从 2025-01 的 100 变化到 2025-02 的 80。报告生成于 2025年。");

		assertEquals(List.of(new BigDecimal("100"), new BigDecimal("80")), numbers);
	}

	@Test
	void findingsWhoseNumbersAreMissingFromEvidenceAreDiscarded() {
		List<AnalysisFinding> retained = validator.retainEvidencedFindings(
				List.of(new AnalysisFinding("合计 100", List.of("ev-1"), List.of(new BigDecimal("100"))),
						new AnalysisFinding("编造 999", List.of("ev-1"), List.of(new BigDecimal("999")))),
				allowed("100"));

		assertEquals(1, retained.size());
		assertEquals("合计 100", retained.get(0).text());
	}

	@Test
	void blankReportsPassAndEmptyWhitelistRejectsAnyNumber() {
		assertTrue(validator.validate("", allowed("1")).passed());
		assertTrue(validator.validate("   ", null).passed());

		ReportNarrativeValidator.ValidationResult result = validator.validate("金额 100 元。", Set.of());

		assertFalse(result.passed());
		assertEquals(List.of("100"), result.unknownValues());
	}

	private Set<BigDecimal> allowed(String... numbers) {
		Set<BigDecimal> values = new LinkedHashSet<>();
		for (String number : numbers) {
			values.add(new BigDecimal(number));
		}
		return values;
	}

}
