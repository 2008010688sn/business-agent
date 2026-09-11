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
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchResultColumnNamerTest {

	@Test
	void catalogAndSqlGrammarNameWarehouseColumnsWithoutAgentDictionary() {
		String sql = """
				SELECT site_name, COUNT(*) AS c1, AVG(stay_days) AS m1,
				SUM(CASE WHEN status = '已签收' THEN qty END) AS m2
				FROM stock GROUP BY site_name
				""";
		SearchResultColumnNamer.Lexicon lexicon = SearchResultColumnNamer.lexicon()
			.addColumn("stock", "site_name", "网点名称")
			.addColumn("stock", "stay_days", "在库天数")
			.addColumn("stock", "qty", "数量");

		Map<String, String> names = SearchResultColumnNamer.resolve(List.of("site_name", "c1", "m1", "m2"), sql,
				lexicon);

		assertEquals(List.of("网点名称", "数量", "平均在库天数", "已签收数量"), List.copyOf(names.values()));
		assertFalse(names.containsValue("维度1"));
		assertFalse(names.containsValue("指标1"));
		assertFalse(names.containsValue("指标2"));
	}

	@Test
	void sqlCaseAndAvgDoNotNeedSemanticSearch() {
		String sql = """
				SELECT one_project_name, two_project_name, COUNT(*) AS bill_count,
				SUM(amount) AS total_amount, AVG(amount) AS avg_amount,
				SUM(CASE WHEN approval_status IN ('已审批', '审批通过') THEN amount END) AS approved_amount,
				SUM(CASE WHEN approval_status IN ('已拒绝') THEN amount END) AS rejected_amount
				FROM bill_cost
				GROUP BY one_project_name, two_project_name
				""";

		Map<String, String> names = SearchResultColumnNamer.resolve(
				List.of("one_project_name", "two_project_name", "bill_count", "total_amount", "avg_amount",
						"approved_amount", "rejected_amount"),
				sql, SearchResultColumnNamer.lexicon().addColumn("bill_cost", "amount", "金额"));

		assertEquals("一级项目", names.get("one_project_name"));
		assertEquals("二级项目", names.get("two_project_name"));
		assertEquals("账单数", names.get("bill_count"));
		assertEquals("总金额", names.get("total_amount"));
		assertEquals("平均金额", names.get("avg_amount"));
		assertEquals("已审批金额", names.get("approved_amount"));
		assertEquals("已拒绝金额", names.get("rejected_amount"));
	}

	@Test
	void placeholderHeadersAreRewrittenBySqlPosition() {
		String sql = "SELECT AVG(stay_days) AS m1, SUM(qty) AS m2 FROM stock";
		Map<String, String> names = SearchResultColumnNamer.resolve(List.of("指标1", "指标2"), sql,
				SearchResultColumnNamer.lexicon()
					.addColumn("stock", "stay_days", "在库天数")
					.addColumn("stock", "qty", "数量"));

		assertEquals("平均在库天数", names.get("指标1"));
		assertEquals("总数量", names.get("指标2"));
	}

	@Test
	void countStarWithoutCatalogFallsBackToQuantity() {
		Map<String, String> names = SearchResultColumnNamer.resolve(List.of("count"), "select count(*) from orders",
				SearchResultColumnNamer.lexicon());

		assertEquals("数量", names.get("count"));
	}

	@Test
	void chineseAliasIsKept() {
		Map<String, String> names = SearchResultColumnNamer.resolve(List.of("平均在库天数"),
				"SELECT AVG(stay_days) AS 平均在库天数 FROM stock", SearchResultColumnNamer.lexicon());

		assertEquals("平均在库天数", names.get("平均在库天数"));
	}

	@Test
	void avgPrefixWithoutCatalogStillUsesGrammar() {
		Map<String, String> names = SearchResultColumnNamer.resolve(List.of("avg_amount"), null,
				SearchResultColumnNamer.lexicon());

		assertEquals("平均金额", names.get("avg_amount"));
		assertTrue(SearchResultColumnNamer.isMetricLabel("平均金额"));
	}

	@Test
	void deletedIsNamedAsDeletionFlagNotCategory() {
		Map<String, String> names = SearchResultColumnNamer.resolve(List.of("deleted", "status"),
				"SELECT deleted, status FROM bill_cost", SearchResultColumnNamer.lexicon());

		assertEquals("删除标识", names.get("deleted"));
		assertEquals("状态", names.get("status"));
		assertFalse(names.containsValue("分类"));
	}

	@Test
	void signTotalFromJoinedSqlResolvesToChineseWithoutSemanticHits() {
		String sql = """
				SELECT d.company_name AS customer_name, SUM(op.sign_num) AS sign_total
				FROM dis_order_product op
				JOIN dis_order o ON o.id = op.order_id
				JOIN dis_demand d ON d.id = o.demand_id
				GROUP BY d.company_name
				""";

		Map<String, String> names = SearchResultColumnNamer.resolve(List.of("customer_name", "sign_total"), sql,
				SearchResultColumnNamer.lexicon());

		assertEquals("客户名称", names.get("customer_name"));
		assertEquals("总签收数", names.get("sign_total"));
	}

	@Test
	void enforceUserFacingResolvesPhysicalColumnWithoutSql() {
		String display = SearchResultColumnNamer.enforceUserFacing("sign_total");

		assertEquals("总签收数", display);
		assertFalse(display.contains("_"));
		assertFalse(display.contains("."));
	}

	@Test
	void enforceUserFacingNeverKeepsPhysicalOriginalForUnknownColumns() {
		// 完全未知的列：数值指标语义倾向兜底"指标"，其余兜底"分类"，均不得回原文。
		assertEquals("指标", SearchResultColumnNamer.enforceUserFacing("unknown_score_total"));
		assertEquals("分类", SearchResultColumnNamer.enforceUserFacing("unknown_label"));
		assertEquals("分类", SearchResultColumnNamer.enforceUserFacing("signTotal"));
	}

	@Test
	void enforceUserFacingKeepsChineseAndSafeShortNames() {
		assertEquals("客户名称", SearchResultColumnNamer.enforceUserFacing("客户名称"));
		assertEquals("总签收数", SearchResultColumnNamer.enforceUserFacing("总签收数"));
		assertEquals("CN", SearchResultColumnNamer.enforceUserFacing("CN"));
	}

	@Test
	void fallbackNamesStayUniqueAndChineseWithoutSql() {
		Map<String, String> names = SearchResultColumnNamer.resolve(List.of("zz_unknown_a", "zz_unknown_b"), null,
				SearchResultColumnNamer.lexicon());

		assertFalse(names.get("zz_unknown_a").contains("_"));
		assertFalse(names.get("zz_unknown_b").contains("_"));
		assertFalse(names.get("zz_unknown_a").equals(names.get("zz_unknown_b")));
	}

}
