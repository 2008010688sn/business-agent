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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportColumnSemanticsTest {

	/**
	 * 列名兜底名回归：后缀合成与中文兜底名必须保住图表候选对金额/数量指标的识别，
	 * 避免兜底把指标列降级成维度导致图表候选丢失。
	 */
	@Test
	void composedAndFallbackNamesKeepMetricSemantics() {
		assertTrue(ReportColumnSemantics.isAmountMetric("总金额"));
		assertTrue(ReportColumnSemantics.isAmountMetric("押金金额"));
		assertTrue(ReportColumnSemantics.isAmountMetric("金额"));
		assertTrue(ReportColumnSemantics.isCountMetric("总签收数"));
		assertTrue(ReportColumnSemantics.isCountMetric("订单数"));
		assertTrue(ReportColumnSemantics.isMetricColumn("总签收数"));
		assertTrue(ReportColumnSemantics.isMetricColumn("账单金额"));
		assertFalse(ReportColumnSemantics.isMetricColumn("客户名称"));
		assertFalse(ReportColumnSemantics.isMetricColumn("网点"));
		assertTrue(SearchResultColumnNamer.isMetricLabel("总签收数"));
		assertTrue(SearchResultColumnNamer.isMetricLabel("平均金额"));
	}

}
