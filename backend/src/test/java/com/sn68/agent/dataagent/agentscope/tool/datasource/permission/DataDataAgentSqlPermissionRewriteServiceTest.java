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
package com.sn68.agent.dataagent.agentscope.tool.datasource.permission;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceRuntimeContextCache;
import com.sn68.agent.dataagent.agentscope.tool.datasource.permission.DataAgentSqlPermissionRewriteService.PermissionRewriteResult;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.entity.DatasourcePermissionRule;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.commons.security.DataPermission;
import com.sn68.agent.framework.commons.security.DataRefType;
import com.sn68.agent.framework.commons.security.DataScopeType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataDataAgentSqlPermissionRewriteServiceTest {

	private static final long DATASOURCE_ID = 10L;

	private final DatasourceService datasourceService = mock(DatasourceService.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final DataAgentProperties dataAgentProperties = new DataAgentProperties();

	private final PepAuthorizationProperties pepAuthorizationProperties = new PepAuthorizationProperties();

	private final DataAgentSqlPermissionRewriteService rewriteService = new DataAgentSqlPermissionRewriteService(
			datasourceService, authenticationContext, new DatasourceRuntimeContextCache(new MockEnvironment()),
			dataAgentProperties, pepAuthorizationProperties);

	@BeforeEach
	void stubTenantScopedPermissionRules() {
		lenient().when(datasourceService.getPermissionRulesForTenant(anyLong(), anyString()))
			.thenAnswer(invocation -> datasourceService.getPermissionRules(invocation.getArgument(0)));
	}

	@Test
	void rewrite_addsAliasQualifiedConditionFromPermissionSnapshot() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		AgentRequest request = requestWithPermission(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of(DataRefType.SITE, List.of("S1", "S2")))
			.build());

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID,
				"select o.id from orders o where o.status = 'PAID'", request);

		assertTrue(result.rewritten());
		assertEquals("SELECT o.id FROM orders o WHERE (o.status = 'PAID') AND (o.site_id IN ('S1', 'S2'))",
				result.sql());
		assertEquals(List.of("orders"), result.appliedTables());
	}

	@Test
	void rewrite_followsXxCloudBehaviorWhenNonUserRuleHasNoPermissionValues() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		AgentRequest request = requestWithPermission(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of())
			.build());

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select * from orders", request);

		assertFalse(result.rewritten());
		assertEquals("select * from orders", result.sql());
		assertTrue(result.denied());
		assertEquals("当前无数据权限", result.denialMessage());
		assertEquals(List.of("orders"), result.appliedTables());
	}

	@Test
	void rewrite_allowsAllMarkerFromXxCloudPermissionMap() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		AgentRequest request = requestWithPermission(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of(DataRefType.SITE, List.of(DataRefType.ALL.getType())))
			.build());

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select * from orders", request);

		assertFalse(result.rewritten());
		assertFalse(result.denied());
		assertEquals("select * from orders", result.sql());
	}

	@Test
	void rewrite_followsXxCloudSelfFallbackForUserRuleWithoutPermissionValues() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID))
			.thenReturn(List.of(rule("orders", "create_by", DataRefType.USER, "String")));
		AgentRequest request = requestWithPermission(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of())
			.build());
		request.setUserIdSnapshot("U1");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select * from orders", request);

		assertTrue(result.rewritten());
		assertEquals("SELECT * FROM orders WHERE orders.create_by = 'U1'", result.sql());
		assertEquals(List.of("orders"), result.appliedTables());
	}

	@Test
	void rewrite_allowsTablesWithoutConfiguredRules() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		AgentRequest request = requestWithPermission(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of(DataRefType.SITE, List.of("S1")))
			.build());

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select * from products p", request);

		assertFalse(result.rewritten());
		assertEquals("select * from products p", result.sql());
		assertEquals(List.of("products"), result.skippedTables());
	}

	@Test
	void rewrite_reusesPermissionRulesWithinSameRuntimeRequest() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		AgentRequest request = requestWithPermission(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of(DataRefType.SITE, List.of("S1")))
			.build());
		request.setThreadId("thread-1");
		request.setRuntimeRequestId("run-1");

		rewriteService.rewrite(DATASOURCE_ID, "select * from orders", request);
		rewriteService.rewrite(DATASOURCE_ID, "select * from orders", request);

		verify(datasourceService, times(1)).getPermissionRules(DATASOURCE_ID);
	}

	@Test
	void routedSkillWithoutPermissionSnapshotAllowsUnmappedTable() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("products", "site_id")));
		AgentRequest request = routedRequest(null);

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders", request);

		assertFalse(result.denied());
		assertEquals("select id from orders", result.sql());
	}

	@Test
	void routedSkillWithoutDatasourcePermissionMappingsIsAllowed() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of());

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders",
				routedRequest(DataPermission.builder().scopeType(DataScopeType.ALL).build()));

		assertFalse(result.denied());
		assertEquals("select id from orders", result.sql());
	}

	@Test
	void routedSkillWithUnmappedReferencedTableIsAllowed() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from products",
				routedRequest(DataPermission.builder().scopeType(DataScopeType.ALL).build()));

		assertFalse(result.denied());
		assertEquals(List.of("products"), result.skippedTables());
	}

	@Test
	void routedSkillIgnoresDisabledPermissionMapping() {
		DatasourcePermissionRule disabled = siteRule("orders", "site_id");
		disabled.setEnabled(false);
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(disabled));

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders", routedRequest(null));

		assertFalse(result.denied());
		assertEquals("select id from orders", result.sql());
	}

	@Test
	void routedSkillFallsBackWhenSnapshotMatchesPrincipalIdentity() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		when(authenticationContext.userId()).thenReturn("sp_abc");
		when(authenticationContext.tenantId()).thenReturn("T1");
		when(authenticationContext.dataPermission()).thenReturn(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of(DataRefType.SITE, List.of("S1")))
			.build());
		AgentRequest request = routedRequest(null);
		request.setUserIdSnapshot("sp_abc");
		request.setTenantIdSnapshot("T1");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders", request);

		assertFalse(result.denied());
		assertEquals("SELECT id FROM orders WHERE orders.site_id IN ('S1')", result.sql());
	}

	@Test
	void routedSkillFallsBackToMatchingLivePermissionContext() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		when(authenticationContext.userId()).thenReturn("U1");
		when(authenticationContext.tenantId()).thenReturn("T1");
		when(authenticationContext.dataPermission()).thenReturn(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of(DataRefType.SITE, List.of("S1")))
			.build());
		AgentRequest request = routedRequest(null);
		request.setUserIdSnapshot("U1");
		request.setTenantIdSnapshot("T1");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders", request);

		assertFalse(result.denied());
		assertEquals("SELECT id FROM orders WHERE orders.site_id IN ('S1')", result.sql());
	}

	@Test
	void routedSkillDeniesMappedQueryWhenNoOriginalPermissionContextCanBeResolved() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders", routedRequest(null));

		assertTrue(result.denied());
		assertEquals("无法解析执行身份的数据权限上下文", result.denialMessage());
	}

	@Test
	void routedSkillAllowsWhenSnapshotIsAllWithoutLiveIdentity() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		AgentRequest request = routedRequest(DataPermission.builder().scopeType(DataScopeType.ALL).build());
		request.setUserIdSnapshot("sp_abc");
		request.setTenantIdSnapshot("T1");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders", request);

		assertFalse(result.denied());
		assertEquals("select id from orders", result.sql());
		verify(datasourceService).getPermissionRulesForTenant(DATASOURCE_ID, "T1");
		verify(authenticationContext, never()).dataPermission();
	}

	@Test
	void routedSkillRejectsMismatchedLivePermissionContext() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		when(authenticationContext.userId()).thenReturn("U1");
		when(authenticationContext.tenantId()).thenReturn("OTHER");
		AgentRequest request = routedRequest(null);
		request.setUserIdSnapshot("U1");
		request.setTenantIdSnapshot("T1");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders", request);

		assertTrue(result.denied());
	}

	@Test
	void routedSkillRejectsLivePermissionFallbackWithoutTenantSnapshot() {
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		when(authenticationContext.userId()).thenReturn("U1");
		AgentRequest request = routedRequest(null);
		request.setUserIdSnapshot("U1");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders", request);

		assertTrue(result.denied());
		verify(authenticationContext, never()).dataPermission();
	}

	@Test
	void enforceTenantWithDenyOnMissingSqlRejectsDatasourceWithoutRules() {
		// PR-4：ENFORCE 租户 + denyOnMissingSql=true——数据源无任何权限映射按 DENY_ON_MISSING 拒绝
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of());
		dataAgentProperties.getAuthorization().setDenyOnMissingSql(true);
		pepAuthorizationProperties.setEnforceTenantIds(List.of("T1"));
		AgentRequest request = routedRequest(DataPermission.builder().scopeType(DataScopeType.ALL).build());
		request.setTenantIdSnapshot("T1");
		request.setThreadId("thread-enforce-deny");
		request.setRuntimeRequestId("run-enforce-deny");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders", request);

		assertTrue(result.denied());
	}

	@Test
	void shadowModeKeepsAllowOnMissingDespiteDenySwitch() {
		// PR-4 SHADOW 红线：denyOnMissingSql=true 但租户未进 ENFORCE 白名单——仍按 ALLOW_ON_MISSING 放行
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of());
		dataAgentProperties.getAuthorization().setDenyOnMissingSql(true);
		AgentRequest request = routedRequest(DataPermission.builder().scopeType(DataScopeType.ALL).build());
		request.setTenantIdSnapshot("T1");
		request.setThreadId("thread-shadow-allow");
		request.setRuntimeRequestId("run-shadow-allow");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID, "select id from orders", request);

		assertFalse(result.denied());
		assertEquals("select id from orders", result.sql());
	}

	@Test
	void enforceTenantWithDenyOnMissingSqlRejectsTableWithoutRules() {
		// PR-4：ENFORCE 租户表级缺策略——orders 有映射、products 无映射时 join 查询整体拒绝
		dataAgentProperties.getAuthorization().setDenyOnMissingSql(true);
		pepAuthorizationProperties.setEnforceTenantIds(List.of("T1"));
		when(datasourceService.getPermissionRules(DATASOURCE_ID)).thenReturn(List.of(siteRule("orders", "site_id")));
		AgentRequest request = requestWithPermission(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of(DataRefType.SITE, List.of("S1")))
			.build());
		request.setTenantIdSnapshot("T1");
		request.setThreadId("thread-table-deny");
		request.setRuntimeRequestId("run-table-deny");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID,
				"select o.id from orders o join products p on o.site_id = p.site_id", request);

		assertTrue(result.denied());
	}

	@Test
	void enforceTenantDeniesUnmappedTableWhenRuleLevelMissingPolicyDemandsDeny() {
		// C2：全局 denyOnMissingSql 关闭，但启用规则声明 missingPolicy=DENY_ON_MISSING——
		// ENFORCE 租户表级缺策略按「取更严者」拒绝，规则级字段不再是死配置
		when(datasourceService.getPermissionRules(DATASOURCE_ID))
				.thenReturn(List.of(siteRuleWithMissingPolicy("orders", "site_id",
						DatasourcePermissionRule.MISSING_POLICY_DENY)));
		pepAuthorizationProperties.setEnforceTenantIds(List.of("T1"));
		AgentRequest request = requestWithPermission(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of(DataRefType.SITE, List.of("S1")))
			.build());
		request.setTenantIdSnapshot("T1");
		request.setThreadId("thread-rule-deny");
		request.setRuntimeRequestId("run-rule-deny");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID,
				"select o.id from orders o join products p on o.site_id = p.site_id", request);

		assertTrue(result.denied());
		// 拒绝来自 products 缺映射路径（规则级策略触发），而非维度权限缺失
		assertTrue(result.messages().stream().anyMatch(message -> message.contains("missingPolicy=DENY_ON_MISSING")));
	}

	@Test
	void enforceTenantStillDeniesUnmappedTableWhenRuleLevelAllowsAndGlobalSwitchOn() {
		// C2：全局 denyOnMissingSql=true + 规则级 missingPolicy=ALLOW——取更严者，
		// 规则级放行不削弱全局拒绝（与上一条合起来覆盖双向取严语义）
		when(datasourceService.getPermissionRules(DATASOURCE_ID))
				.thenReturn(List.of(siteRuleWithMissingPolicy("orders", "site_id",
						DatasourcePermissionRule.MISSING_POLICY_ALLOW)));
		dataAgentProperties.getAuthorization().setDenyOnMissingSql(true);
		pepAuthorizationProperties.setEnforceTenantIds(List.of("T1"));
		AgentRequest request = requestWithPermission(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of(DataRefType.SITE, List.of("S1")))
			.build());
		request.setTenantIdSnapshot("T1");
		request.setThreadId("thread-global-deny");
		request.setRuntimeRequestId("run-global-deny");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID,
				"select o.id from orders o join products p on o.site_id = p.site_id", request);

		assertTrue(result.denied());
	}

	@Test
	void shadowModeKeepsAllowOnMissingDespiteRuleLevelDenyPolicy() {
		// C2 SHADOW 红线：规则级 DENY_ON_MISSING + 全局 denyOnMissingSql=true，但租户未进
		// ENFORCE 白名单——SHADOW 保持 ALLOW_ON_MISSING 记录行为不变，不因规则级字段改现网行为
		when(datasourceService.getPermissionRules(DATASOURCE_ID))
				.thenReturn(List.of(siteRuleWithMissingPolicy("orders", "site_id",
						DatasourcePermissionRule.MISSING_POLICY_DENY)));
		dataAgentProperties.getAuthorization().setDenyOnMissingSql(true);
		AgentRequest request = requestWithPermission(DataPermission.builder()
			.scopeType(DataScopeType.CUSTOMIZE)
			.dataPermissionMap(Map.of(DataRefType.SITE, List.of("S1")))
			.build());
		request.setTenantIdSnapshot("T1");
		request.setThreadId("thread-shadow-rule-allow");
		request.setRuntimeRequestId("run-shadow-rule-allow");

		PermissionRewriteResult result = rewriteService.rewrite(DATASOURCE_ID,
				"select o.id from orders o join products p on o.site_id = p.site_id", request);

		assertFalse(result.denied());
		assertEquals(List.of("products"), result.skippedTables());
		assertTrue(result.rewritten());
	}

	private static DatasourcePermissionRule siteRule(String tableName, String columnName) {
		return rule(tableName, columnName, DataRefType.SITE, "String");
	}

	private static DatasourcePermissionRule siteRuleWithMissingPolicy(String tableName, String columnName,
			String missingPolicy) {
		DatasourcePermissionRule rule = rule(tableName, columnName, DataRefType.SITE, "String");
		rule.setMissingPolicy(missingPolicy);
		return rule;
	}

	private static DatasourcePermissionRule rule(String tableName, String columnName, DataRefType dataRefType,
			String javaType) {
		return DatasourcePermissionRule.builder()
			.datasourceId(DATASOURCE_ID)
			.tableName(tableName)
			.columnName(columnName)
			.dataRefType(dataRefType.getType())
			.javaType(javaType)
			.enabled(true)
			.missingPolicy(DatasourcePermissionRule.MISSING_POLICY_ALLOW)
			.build();
	}

	private static AgentRequest requestWithPermission(DataPermission dataPermission) {
		AgentRequest request = new AgentRequest();
		request.setDataPermissionSnapshot(dataPermission);
		return request;
	}

	private static AgentRequest routedRequest(DataPermission dataPermission) {
		AgentRequest request = requestWithPermission(dataPermission);
		request.setRoutedSkillId(2L);
		request.setRoutedSkillVersionId(3L);
		return request;
	}

}
