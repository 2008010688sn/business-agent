/*
 * Copyright 2026 the original author or authors.
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
package com.sn68.agent.dataagent.agentscope.tool.sqlguard;

import com.sn68.agent.dataagent.connector.accessor.AccessorFactory;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SqlVerifyExplainServiceTest {

	private final SqlVerifyExplainService service = new SqlVerifyExplainService(mock(DatasourceService.class),
			new AccessorFactory(List.of()), new DataAgentProperties());

	@Test
	void explain_allowsTopNAbovePlatformLimitWhenSqlCoversPlatformMaximum() {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery("查询客户需求单数量前300名");
		request.setSql(
				"select customer_name, count(1) as order_count from demand_order group by customer_name order by order_count desc limit 300");

		SqlGuardCheckResult result = service.explain(request);

		assertTrue(result.getIsAligned());
		assertTrue(result.getRuleChecks()
			.stream()
			.anyMatch(rule -> "LIMIT_MATCH".equals(rule.getCode()) && "PASSED".equals(rule.getStatus())));
	}

	@Test
	void explain_rejectsSelectStarBeforeDatasourceExecution() {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery("查询 D020260528-689 的详情");
		request.setSql("select * from orders where bill_no = 'D020260528-689'");

		SqlGuardCheckResult result = service.explain(request);

		assertFalse(result.getIsAligned());
		assertEquals("SELECT_STAR_FORBIDDEN", result.getErrorCode());
		assertTrue(result.getProblems().stream().anyMatch(problem -> "SELECT_STAR_FORBIDDEN".equals(problem.getCode())));
		assertTrue(result.getRuleChecks().stream().anyMatch(rule -> "SELECT_STAR_FORBIDDEN".equals(rule.getCode())));
	}

	@Test
	void explain_rejectsSelectDeleted() {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery("上个月各项目的账单情况");
		request.setSql(
				"select settlement_date, amount, one_project_name, status, deleted from bill_cost where settlement_date is not null limit 5");

		SqlGuardCheckResult result = service.explain(request);

		assertFalse(result.getIsAligned());
		assertTrue(result.getProblems().stream().anyMatch(problem -> "SELECT_DELETED_FORBIDDEN".equals(problem.getCode())));
		assertTrue(result.getRuleChecks()
			.stream()
			.anyMatch(rule -> "SELECT_DELETED_FORBIDDEN".equals(rule.getCode()) && "FAILED".equals(rule.getStatus())));
	}

	@Test
	void explain_allowsDeletedFalseFilterWithoutSelectingDeleted() {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery("上个月各项目的账单情况");
		request.setSql(
				"select one_project_name, sum(amount) as total_amount, count(*) as bill_count from bill_cost where settlement_date like '2026-08%' and deleted = false group by one_project_name order by total_amount desc");

		SqlGuardCheckResult result = service.explain(request);

		assertTrue(result.getIsAligned());
		assertTrue(result.getRuleChecks()
			.stream()
			.anyMatch(rule -> "SELECT_DELETED_FORBIDDEN".equals(rule.getCode()) && "PASSED".equals(rule.getStatus())));
	}

	@Test
	void explain_allowsCountStar() {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery("统计订单数量");
		request.setSql("select count(*) as total_count from orders");

		SqlGuardCheckResult result = service.explain(request);

		assertTrue(result.getIsAligned());
		assertTrue(result.getRuleChecks()
			.stream()
			.anyMatch(rule -> "SELECT_STAR_FORBIDDEN".equals(rule.getCode()) && "PASSED".equals(rule.getStatus())));
	}

	@Test
	void explain_doesNotTreatBusinessCodeAsTimeFilter() {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery("查询账单号为 D020260528-689 的详情");
		request.setSql(
				"select code, company_name, amount, create_time from bill_cost where code = 'D020260528-689' and deleted = false");

		SqlGuardCheckResult result = service.explain(request);

		assertTrue(result.getIsAligned());
		assertTrue(result.getProblems().stream().noneMatch(problem -> "MISSING_TIME_FILTER".equals(problem.getCode())));
		assertTrue(result.getRuleChecks()
			.stream()
			.noneMatch(rule -> "TIME_FILTER_REQUIRED".equals(rule.getCode())));
	}

	@Test
	void explain_requiresTimePredicateForExplicitYearEvenWhenSelectingTimeColumn() {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery("查询2026年的账单详情");
		request.setSql("select code, company_name, amount, create_time from bill_cost where deleted = false");

		SqlGuardCheckResult result = service.explain(request);

		assertFalse(result.getIsAligned());
		assertTrue(result.getProblems().stream().anyMatch(problem -> "MISSING_TIME_FILTER".equals(problem.getCode())));
	}

	@Test
	void explain_rejectsTopNLimitSmallerThanRequested() {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery("查询客户需求单数量前10名");
		request.setSql(
				"select customer_name, count(1) as order_count from demand_order group by customer_name order by order_count desc limit 5");

		SqlGuardCheckResult result = service.explain(request);

		assertFalse(result.getIsAligned());
		assertTrue(result.getProblems().stream().anyMatch(problem -> "LIMIT_TOO_SMALL".equals(problem.getCode())));
	}

	@Test
	void explain_allowsTopNLimitEqualToRequested() {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery("查询客户需求单数量前10名");
		request.setSql(
				"select customer_name, count(1) as order_count from demand_order group by customer_name order by order_count desc limit 10");

		SqlGuardCheckResult result = service.explain(request);

		assertTrue(result.getIsAligned());
		assertTrue(result.getRuleChecks()
			.stream()
			.anyMatch(rule -> "LIMIT_MATCH".equals(rule.getCode()) && "PASSED".equals(rule.getStatus())));
	}

	@Test
	void explain_doesNotTreatSubqueryLimitAsOuterTopNLimit() {
		SqlGuardCheckRequest request = new SqlGuardCheckRequest();
		request.setQuery("查询客户需求单数量前10名");
		request.setSql("""
				select customer_name, sum(order_count) as order_count
				from (
				  select customer_name, count(1) as order_count
				  from demand_order
				  group by customer_name
				  limit 5
				) source_data
				group by customer_name
				order by order_count desc
				""");

		SqlGuardCheckResult result = service.explain(request);

		assertFalse(result.getIsAligned());
		assertTrue(result.getProblems().stream().anyMatch(problem -> "MISSING_LIMIT".equals(problem.getCode())));
	}

}
