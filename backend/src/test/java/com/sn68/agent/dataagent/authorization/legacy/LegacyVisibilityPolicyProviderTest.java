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
package com.sn68.agent.dataagent.authorization.legacy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.authorization.dto.AuthorizationSubjectSnapshot;
import com.sn68.agent.dataagent.constant.AgentVisibilityConstant;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityGrant;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityPolicy;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityApplicationMapper;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityGrantMapper;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityPolicyMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.visibility.AgentVisibilityApprovalAdapter;
import com.sn68.agent.dataagent.service.visibility.DataAgentVisibilityServiceImpl;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Legacy 适配等价性测试（PR-3b）。
 *
 * <p>锚点：真实实例化现网 {@link DataAgentVisibilityServiceImpl}（Mockito mock 其全部依赖），
 * 对同一组输入断言 {@link LegacyVisibilityPolicyProvider#canUse} 与现网
 * {@code canVisibleInUserWorkbench} 结果一致；canDiscover 按现网 canListInCatalog 源码语义手工断言。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@SuppressWarnings("unchecked")
class LegacyVisibilityPolicyProviderTest {

	private static final Long AGENT_ID = 1L;

	private static final String USER_ID = "u1";

	private static final String TENANT_ID = "t1";

	private DataAgentVisibilityPolicyMapper legacyPolicyMapper;

	private DataAgentVisibilityGrantMapper legacyGrantMapper;

	private DataAgentService dataAgentService;

	private AuthenticationContext authenticationContext;

	private LegacyVisibilityPolicyProvider provider;

	private final DataAgentProperties dataAgentProperties = new DataAgentProperties();

	private DataAgentVisibilityServiceImpl legacyService;

	@BeforeEach
	void setUp() {
		legacyPolicyMapper = mock(DataAgentVisibilityPolicyMapper.class);
		legacyGrantMapper = mock(DataAgentVisibilityGrantMapper.class);
		provider = new LegacyVisibilityPolicyProvider(legacyPolicyMapper, legacyGrantMapper, dataAgentProperties);

		dataAgentService = mock(DataAgentService.class);
		DataAgentMapper dataAgentMapper = mock(DataAgentMapper.class);
		DataAgentVisibilityApplicationMapper applicationMapper = mock(DataAgentVisibilityApplicationMapper.class);
		authenticationContext = mock(AuthenticationContext.class);
		List<AgentVisibilityApprovalAdapter> approvalAdapters = List.of();
		legacyService = new DataAgentVisibilityServiceImpl(dataAgentService, dataAgentMapper, legacyPolicyMapper,
				legacyGrantMapper, applicationMapper, authenticationContext, approvalAdapters, provider);

		// 非管理员普通用户上下文（各用例按需覆盖）
		stubContext(false);
	}

	@Test
	void unpublishedAgentIsDeniedOnBothViews() {
		publishAgentWithStatus("disabled");
		DataAgentVisibilityPolicy policy = dbPolicy(AgentVisibilityConstant.CONVERSATION_SCOPE_TENANT,
				AgentVisibilityConstant.CATALOG_SCOPE_TENANT, AgentVisibilityConstant.POLICY_STATUS_ENABLED);
		when(legacyPolicyMapper.findByAgentId(AGENT_ID)).thenReturn(policy);

		assertFalse(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertFalse(provider.canUse(AGENT_ID, "disabled", snapshot(false)));
		assertFalse(provider.canDiscover(AGENT_ID, "disabled", snapshot(false)));
	}

	@Test
	void adminAlwaysAllowedMatchingLegacyBehavior() {
		publishAgentWithStatus("published");
		stubContext(true);
		when(legacyPolicyMapper.findByAgentId(AGENT_ID)).thenReturn(null);
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID)).thenReturn(List.of());

		assertTrue(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertTrue(provider.canUse(AGENT_ID, "published", snapshot(true)));
		assertTrue(provider.canDiscover(AGENT_ID, "published", snapshot(true)));
	}

	@Test
	void disabledPolicyDeniesBothViews() {
		publishAgentWithStatus("published");
		DataAgentVisibilityPolicy policy = dbPolicy(AgentVisibilityConstant.CONVERSATION_SCOPE_TENANT,
				AgentVisibilityConstant.CATALOG_SCOPE_TENANT, AgentVisibilityConstant.POLICY_STATUS_DISABLED);
		when(legacyPolicyMapper.findByAgentId(AGENT_ID)).thenReturn(policy);
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID)).thenReturn(List.of());

		assertFalse(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertFalse(provider.canUse(AGENT_ID, "published", snapshot(false)));
		// 现网 canListInCatalog：policy 非 ENABLED → false
		assertFalse(provider.canDiscover(AGENT_ID, "published", snapshot(false)));
	}

	@Test
	void defaultPolicyGrantsDiscoveryButNotUse() {
		// 无 DB 策略记录：现网默认策略 conversation=GRANT_ONLY, catalog=TENANT
		publishAgentWithStatus("published");
		when(legacyPolicyMapper.findByAgentId(AGENT_ID)).thenReturn(null);
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID)).thenReturn(List.of());

		assertFalse(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertFalse(provider.canUse(AGENT_ID, "published", snapshot(false)));
		assertTrue(provider.canDiscover(AGENT_ID, "published", snapshot(false)));
	}

	@Test
	void tenantConversationScopeAllowsUse() {
		publishAgentWithStatus("published");
		when(legacyPolicyMapper.findByAgentId(AGENT_ID))
			.thenReturn(dbPolicy(AgentVisibilityConstant.CONVERSATION_SCOPE_TENANT,
					AgentVisibilityConstant.CATALOG_SCOPE_HIDDEN, AgentVisibilityConstant.POLICY_STATUS_ENABLED));
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID)).thenReturn(List.of());

		assertTrue(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertTrue(provider.canUse(AGENT_ID, "published", snapshot(false)));
		// 可见即目录可见
		assertTrue(provider.canDiscover(AGENT_ID, "published", snapshot(false)));
	}

	@Test
	void userGrantMatchesBothViews() {
		publishAgentWithStatus("published");
		when(legacyPolicyMapper.findByAgentId(AGENT_ID)).thenReturn(null);
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID))
			.thenReturn(List.of(grant(AgentVisibilityConstant.SUBJECT_TYPE_USER, USER_ID)));

		assertTrue(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertTrue(provider.canUse(AGENT_ID, "published", snapshot(false)));
		assertTrue(provider.canDiscover(AGENT_ID, "published", snapshot(false)));
	}

	@Test
	void tenantedGrantMatchesBothViews() {
		publishAgentWithStatus("published");
		when(legacyPolicyMapper.findByAgentId(AGENT_ID)).thenReturn(null);
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID))
			.thenReturn(List.of(grant(AgentVisibilityConstant.SUBJECT_TYPE_TENANT, TENANT_ID)));

		assertTrue(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertTrue(provider.canUse(AGENT_ID, "published", snapshot(false)));
	}

	@Test
	void teamScopeMatchesOnlyWithTeamGrant() {
		publishAgentWithStatus("published");
		when(legacyPolicyMapper.findByAgentId(AGENT_ID))
			.thenReturn(dbPolicy(AgentVisibilityConstant.CONVERSATION_SCOPE_TEAM,
					AgentVisibilityConstant.CATALOG_SCOPE_TEAM, AgentVisibilityConstant.POLICY_STATUS_ENABLED));

		// 无团队授权：两侧均拒
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID)).thenReturn(List.of());
		assertFalse(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertFalse(provider.canUse(AGENT_ID, "published", snapshot(false)));
		assertFalse(provider.canDiscover(AGENT_ID, "published", snapshot(false)));

		// 命中团队授权：两侧均放行
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID))
			.thenReturn(List.of(grant(AgentVisibilityConstant.SUBJECT_TYPE_TEAM, "team-1")));
		assertTrue(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertTrue(provider.canUse(AGENT_ID, "published", snapshot(false)));
		assertTrue(provider.canDiscover(AGENT_ID, "published", snapshot(false)));
	}

	@Test
	void permissionScopeMatchesOnlyWithPermissionGrant() {
		publishAgentWithStatus("published");
		when(legacyPolicyMapper.findByAgentId(AGENT_ID))
			.thenReturn(dbPolicy(AgentVisibilityConstant.CONVERSATION_SCOPE_PERMISSION,
					AgentVisibilityConstant.CATALOG_SCOPE_PERMISSION,
					AgentVisibilityConstant.POLICY_STATUS_ENABLED));
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID))
			.thenReturn(List.of(grant(AgentVisibilityConstant.SUBJECT_TYPE_PERMISSION, "perm-1")));

		assertTrue(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertTrue(provider.canUse(AGENT_ID, "published", snapshot(false)));
		assertTrue(provider.canDiscover(AGENT_ID, "published", snapshot(false)));
	}

	@Test
	void hiddenCatalogDeniesDiscoveryWhileGrantOnlyDeniesUse() {
		publishAgentWithStatus("published");
		when(legacyPolicyMapper.findByAgentId(AGENT_ID))
			.thenReturn(dbPolicy(AgentVisibilityConstant.CONVERSATION_SCOPE_GRANT_ONLY,
					AgentVisibilityConstant.CATALOG_SCOPE_HIDDEN, AgentVisibilityConstant.POLICY_STATUS_ENABLED));
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID)).thenReturn(List.of());

		assertFalse(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertFalse(provider.canUse(AGENT_ID, "published", snapshot(false)));
		assertFalse(provider.canDiscover(AGENT_ID, "published", snapshot(false)));
	}

	@Test
	void revokedGrantNeverMatches() {
		publishAgentWithStatus("published");
		when(legacyPolicyMapper.findByAgentId(AGENT_ID)).thenReturn(null);
		DataAgentVisibilityGrant revoked = DataAgentVisibilityGrant.builder()
			.subjectType(AgentVisibilityConstant.SUBJECT_TYPE_USER)
			.subjectId(USER_ID)
			.status(AgentVisibilityConstant.GRANT_STATUS_REVOKED)
			.build();
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID)).thenReturn(List.of(revoked));

		assertFalse(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertFalse(provider.canUse(AGENT_ID, "published", snapshot(false)));
	}

	@Test
	void mismatchedUserGrantDoesNotMatch() {
		publishAgentWithStatus("published");
		when(legacyPolicyMapper.findByAgentId(AGENT_ID)).thenReturn(null);
		when(legacyGrantMapper.listActiveByAgentId(AGENT_ID))
			.thenReturn(List.of(grant(AgentVisibilityConstant.SUBJECT_TYPE_USER, "someone-else")));

		assertFalse(legacyService.canVisibleInUserWorkbench(AGENT_ID));
		assertFalse(provider.canUse(AGENT_ID, "published", snapshot(false)));
		// 等价 canListInCatalog + defaultPolicy：无策略记录时 catalogScope 默认 TENANT，
		// grant 不匹配仅阻断“使用”，不阻断“发现”（目录可见但工作台不可见）。
		assertTrue(provider.canDiscover(AGENT_ID, "published", snapshot(false)));
	}

	@Test
	void freezeLegacyVisibilityWritesRejectsWriteAssertion() {
		// PR-4：freezeLegacyVisibilityWrites=true 时旧写路径冻结（fail-closed），读判定不受影响
		dataAgentProperties.getAuthorization().setFreezeLegacyVisibilityWrites(true);

		assertThrows(CheckedException.class, () -> provider.assertWritesNotFrozen());
	}

	@Test
	void writeAssertionPassesWhenFreezeDisabledByDefault() {
		// PR-4：默认 false 现网旧接口零变化
		assertDoesNotThrow(() -> provider.assertWritesNotFrozen());
	}

	/**
	 * 构造主体快照：u1/t1，团队 team-1，功能权限 perm-1。
	 */
	private AuthorizationSubjectSnapshot snapshot(boolean admin) {
		return AuthorizationSubjectSnapshot.builder()
			.userId(USER_ID)
			.tenantId(TENANT_ID)
			.teamIds(List.of("team-1"))
			.funcPermissions(List.of("perm-1"))
			.admin(admin)
			.build();
	}

	/**
	 * 构造生效授权记录（ACTIVE、未过期）。
	 */
	private DataAgentVisibilityGrant grant(String subjectType, String subjectId) {
		return DataAgentVisibilityGrant.builder()
			.agentId(AGENT_ID)
			.subjectType(subjectType)
			.subjectId(subjectId)
			.status(AgentVisibilityConstant.GRANT_STATUS_ACTIVE)
			.build();
	}

	/**
	 * 构造 DB 可见性策略记录。
	 */
	private DataAgentVisibilityPolicy dbPolicy(String conversationScope, String catalogScope, String status) {
		return DataAgentVisibilityPolicy.builder()
			.agentId(AGENT_ID)
			.conversationScope(conversationScope)
			.catalogScope(catalogScope)
			.applyMode(AgentVisibilityConstant.APPLY_MODE_APPROVAL_REQUIRED)
			.approvalMode(AgentVisibilityConstant.APPROVAL_MODE_LOCAL)
			.status(status)
			.build();
	}

	/**
	 * 发布指定状态的 Agent（现网经 DataAgentService 读取）。
	 */
	private void publishAgentWithStatus(String status) {
		DataAgent agent = DataAgent.builder()
			.id(AGENT_ID)
			.status(status)
			.tenantId(TENANT_ID)
			.build();
		when(dataAgentService.findById(AGENT_ID)).thenReturn(agent);
	}

	/**
	 * 现网服务认证上下文（含管理员/普通用户两种画像）。
	 */
	private void stubContext(boolean admin) {
		when(authenticationContext.anonymous()).thenReturn(false);
		when(authenticationContext.userId()).thenReturn(USER_ID);
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
		when(authenticationContext.teamIds()).thenReturn(List.of("team-1"));
		when(authenticationContext.funcPermissionList()).thenReturn(List.of("perm-1"));
		when(authenticationContext.userType()).thenReturn(admin ? UserType.TENANT_ADMIN : UserType.IN);
		// isCurrentAdmin 要求 getContext() 非 null
		when(authenticationContext.getContext()).thenReturn(new Object());
	}

}
