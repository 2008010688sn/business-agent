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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportSnapshotSelectorTest {

	private final ReportSnapshotSelector selector = new ReportSnapshotSelector();

	@Test
	void rankingBeatsLaterSingleRowDetail() {
		ReportDataSnapshot ranking = ReportDataSnapshot.builder()
			.title("排名")
			.columns(List.of("客户名称", "订单数"))
			.rows(List.of(Map.of("客户名称", "客户1", "订单数", new BigDecimal("9")),
					Map.of("客户名称", "客户2", "订单数", new BigDecimal("8"))))
			.coverageStatus(ResultCoverageStatus.FULL)
			.ranking(true)
			.build();
		ReportDataSnapshot detail = ReportDataSnapshot.builder()
			.title("详情")
			.columns(List.of("客户名称", "订单数"))
			.rows(List.of(Map.of("客户名称", "客户1", "订单数", new BigDecimal("9"))))
			.coverageStatus(ResultCoverageStatus.FULL)
			.build();

		ReportSnapshotSelector.Selection selection = selector.select(List.of(ranking, detail));

		assertEquals(ranking, selection.primary().orElseThrow());
		assertEquals(List.of(ranking), selection.forCharts());
		assertTrue(selection.ranking().isPresent());
	}

	@Test
	void richerSnapshotDominatesSameRowIncompleteSnapshot() {
		ReportDataSnapshot incomplete = ReportDataSnapshot.builder()
			.title("中间结果")
			.columns(List.of("一级项目", "账单数", "总金额"))
			.rows(List.of(Map.of("一级项目", "测试一级项目", "账单数", new BigDecimal("55"), "总金额",
					new BigDecimal("86250")),
					Map.of("一级项目", "太阳食品", "账单数", new BigDecimal("1"), "总金额", new BigDecimal("2730"))))
			.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
			.build();
		ReportDataSnapshot complete = ReportDataSnapshot.builder()
			.title("完整结果")
			.columns(List.of("一级项目", "二级项目", "账单数", "总金额", "总运费", "服务费"))
			.rows(List.of(Map.of("一级项目", "测试一级项目", "二级项目", "测试二级项目", "账单数",
					new BigDecimal("55"), "总金额", new BigDecimal("167650"), "总运费", new BigDecimal("2040"),
					"服务费", new BigDecimal("1020")),
					Map.of("一级项目", "太阳食品", "二级项目", "太阳饮料", "账单数", new BigDecimal("1"),
							"总金额", new BigDecimal("2730"), "总运费", new BigDecimal("13"), "服务费",
							new BigDecimal("12"))))
			.coverageStatus(ResultCoverageStatus.SOURCE_SHORT)
			.build();

		ReportSnapshotSelector.Selection selection = selector.select(List.of(incomplete, complete));

		assertEquals(complete, selection.primary().orElseThrow());
		assertEquals(List.of(complete), selection.forCharts());
	}

}
