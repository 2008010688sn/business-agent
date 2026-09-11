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

class ReportIntentDetectorTest {

	private final ReportIntentDetector detector = new ReportIntentDetector();

	@Test
	void shouldGenerateReportWhenModeIsReport() {
		assertTrue(detector.shouldGenerateReport("统计本月销售额", "report"));
	}

	@Test
	void shouldGenerateReportWhenQueryHasReportIntent() {
		assertTrue(detector.shouldGenerateReport("请基于本次分析生成一份经营分析报告", "normal"));
		assertTrue(detector.shouldGenerateReport("输出周报并给出行动建议", null));
		assertTrue(detector.shouldGenerateReport("我要下载报告", "normal"));
	}

	@Test
	void shouldNotGenerateReportForBusinessEntityQuestions() {
		assertFalse(detector.shouldGenerateReport("报告编号 A001 对应的状态是什么", "normal"));
		assertFalse(detector.shouldGenerateReport("报表数量有多少", "normal"));
		assertFalse(detector.shouldGenerateReport("查询日报表里面的指标口径", "normal"));
	}

	@Test
	void shouldNotGenerateReportForPlainBusinessAnalysis() {
		assertFalse(detector.shouldGenerateReport("经营分析本月订单", "normal"));
		assertFalse(detector.shouldGenerateReport("形成分析口径", "normal"));
	}

}
