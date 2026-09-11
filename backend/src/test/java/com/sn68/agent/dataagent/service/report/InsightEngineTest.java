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

import com.sn68.agent.dataagent.agentscope.tool.datasource.ResultCoverageStatus;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightEngineTest {

	private final InsightEngine engine = new InsightEngine();

	@Test
	void usesBusinessNameDimensionAndAmountMetricInsteadOfIdsAndCounts() {
		InsightEngine.InsightPack pack = engine.analyze(selector().select(List.of(billSnapshot())), explain());

		String facts = String.join("\n", pack.factLines());
		String interpretation = String.join("\n", pack.interpretationLines());
		String actions = String.join("\n", pack.actionLines());
		String risks = String.join("\n", pack.riskLines());
		assertTrue(facts.contains("测试一级项目") || facts.contains("测试二级项目"));
		assertTrue(facts.contains("总金额"));
		assertFalse(facts.contains("2038477980368474114"));
		assertFalse(facts.contains("编号"));
		assertFalse(interpretation.contains("头部项"));
		assertFalse(actions.contains("覆盖范围是否被截断"));
		assertTrue(actions.contains("测试一级项目"));
		assertTrue(risks.contains("测试") || interpretation.contains("测试或演示"));
		assertTrue(pack.kpiLines().stream().anyMatch(line -> line.contains("已覆盖当前查询范围内的全部结果")));
		assertTrue(pack.rootCauses().stream().noneMatch(cause -> cause.evidenceIds().isEmpty()));
		assertTrue(pack.rootCauses().stream().noneMatch(cause -> cause.claim().contains("尚未核对的口径差异")));
		assertTrue(pack.findings().stream().noneMatch(finding -> finding.evidenceIds().isEmpty()));
	}

	@Test
	void sourceShortDoesNotAskToRecheckTruncation() {
		InsightEngine.InsightPack pack = engine.analyze(selector().select(List.of(namedAmountSnapshot())), explain());

		String actions = String.join("\n", pack.actionLines());
		assertFalse(actions.contains("截断"));
		assertTrue(actions.contains("太阳食品") || actions.contains("总金额"));
	}

	@Test
	void usesAllSkillActionDirectionsWithCapOfThree() {
		AnswerTraceExplainView profileExplain = AnswerTraceExplainView.builder()
			.question("这个月各项目的账单情况")
			.skillReportProfile(SkillReportProfile.builder()
				.actionDirections(List.of("核对一级项目口径", "复核运费构成", "确认账单数统计范围", "补充回箱数据", "对比上月"))
				.build())
			.build();

		InsightEngine.InsightPack pack = engine.analyze(selector().select(List.of(namedAmountSnapshot())), profileExplain);

		String actions = String.join("\n", pack.actionLines());
		assertTrue(actions.contains("核对一级项目口径"));
		assertTrue(actions.contains("优先关注「太阳食品」"));
		assertTrue(actions.contains("复核运费构成"));
		assertTrue(actions.contains("确认账单数统计范围"));
		assertFalse(actions.contains("补充回箱数据"));
		assertFalse(actions.contains("对比上月"));
	}

	@Test
	void timeSeriesFactIsNotDroppedBecauseYearMonthTokensAreLabels() {
		InsightEngine.InsightPack pack = engine.analyze(selector().select(List.of(monthlyAmountSnapshot())), explain());

		assertTrue(pack.factLines().stream()
			.anyMatch(line -> line.contains("2025-01") && line.contains("变化到") && line.contains("2025-02")));
		assertTrue(pack.findings().stream()
			.anyMatch(finding -> finding.text().contains("2025-01") && finding.text().contains("变化到")
					&& finding.text().contains("100") && !finding.evidenceIds().isEmpty()));
	}

	private ReportSnapshotSelector selector() {
		return new ReportSnapshotSelector();
	}

	private AnswerTraceExplainView explain() {
		return AnswerTraceExplainView.builder().question("这个月各项目的账单情况").build();
	}

	private ReportDataSnapshot billSnapshot() {
		return ReportDataSnapshot.builder()
			.title("本次分析结果")
			.columns(List.of("维度1", "维度2", "账单数", "总金额", "总运费"))
			.rows(List.of(row("2038477980368474114", "测试一级项目", "55", "167650", "2040"),
					row("ed4645ecb54f4e7da2f542bc267a7fe4", "太阳食品", "1", "2730", "13"),
					row("1916682502147461121", "欣欣黄花菜1", "2", "2400", "20"),
					row("1967502085764870145", "咖啡", "2", "2240", "56"),
					row("1967502085764870146", "星巴克咖啡", "3", "725", "40"),
					row("baf094bbba394bcea2a0d6f33b07c6b5", "统万", "1", "292", "10")))
			.requestedRows(20)
			.returnedRows(3)
			.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
			.build();
	}

	private ReportDataSnapshot monthlyAmountSnapshot() {
		return ReportDataSnapshot.builder()
			.title("月度金额")
			.columns(List.of("月份", "总金额"))
			.rows(List.of(Map.of("月份", "2025-01", "总金额", new BigDecimal("100")),
					Map.of("月份", "其他", "总金额", new BigDecimal("50")),
					Map.of("月份", "2025-02", "总金额", new BigDecimal("80"))))
			.requestedRows(20)
			.returnedRows(3)
			.coverageStatus(ResultCoverageStatus.FULL)
			.build();
	}

	private ReportDataSnapshot namedAmountSnapshot() {
		return ReportDataSnapshot.builder()
			.title("项目金额")
			.columns(List.of("一级项目", "总金额"))
			.rows(List.of(Map.of("一级项目", "太阳食品", "总金额", new BigDecimal("2730")),
					Map.of("一级项目", "统万", "总金额", new BigDecimal("292"))))
			.requestedRows(20)
			.returnedRows(2)
			.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
			.build();
	}

	private Map<String, Object> row(String id, String name, String count, String amount, String fare) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("维度1", id);
		values.put("维度2", name);
		values.put("账单数", new BigDecimal(count));
		values.put("总金额", new BigDecimal(amount));
		values.put("总运费", new BigDecimal(fare));
		return values;
	}

}
