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
package com.sn68.agent.dataagent.service.visibility;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.authorization.legacy.LegacyVisibilityPolicyProvider;
import com.sn68.agent.dataagent.constant.AgentVisibilityConstant;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityPolicyReq;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityPolicy;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityApplicationMapper;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityGrantMapper;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityPolicyMapper;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * freezeLegacyVisibilityWrites 旧可见性写路径接线测试（PR-4 写冻结消费方）。
 *
 * <p>锚点：真实实例化 {@link DataAgentVisibilityServiceImpl}（Mockito mock 其余依赖），
 * 断言开关 true 时写入口在触碰 Legacy 三表前抛 {@link CheckedException}（提示指引走 PAP），
 * 默认 false 时写路径行为不变。</p>
 */
@SuppressWarnings("unchecked")
class DataAgentVisibilityServiceImplTest {

	private static final Long AGENT_ID = 1L;

	private DataAgentVisibilityPolicyMapper policyMapper;

	private DataAgentVisibilityGrantMapper grantMapper;

	private DataAgentService dataAgentService;

	private DataAgentProperties dataAgentProperties;

	private DataAgentVisibilityServiceImpl service;

	@BeforeEach
	void setUp() {
		policyMapper = mock(DataAgentVisibilityPolicyMapper.class);
		grantMapper = mock(DataAgentVisibilityGrantMapper.class);
		dataAgentService = mock(DataAgentService.class);
		dataAgentProperties = new DataAgentProperties();
		LegacyVisibilityPolicyProvider provider = new LegacyVisibilityPolicyProvider(policyMapper, grantMapper,
				dataAgentProperties);
		DataAgentMapper dataAgentMapper = mock(DataAgentMapper.class);
		DataAgentVisibilityApplicationMapper applicationMapper = mock(DataAgentVisibilityApplicationMapper.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		List<AgentVisibilityApprovalAdapter> approvalAdapters = List.of();
		service = new DataAgentVisibilityServiceImpl(dataAgentService, dataAgentMapper, policyMapper, grantMapper,
				applicationMapper, authenticationContext, approvalAdapters, provider);
	}

	@Test
	void modifyPolicyRejectedBeforeTouchingLegacyTablesWhenFrozen() {
		dataAgentProperties.getAuthorization().setFreezeLegacyVisibilityWrites(true);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.modifyPolicy(AGENT_ID, validPolicyReq()));

		assertTrue(ex.getMessage().contains("冻结"));
		assertTrue(ex.getMessage().contains("PAP"));
		verifyNoInteractions(dataAgentService, policyMapper);
	}

	@Test
	void deleteGrantRejectedBeforeTouchingLegacyTablesWhenFrozen() {
		dataAgentProperties.getAuthorization().setFreezeLegacyVisibilityWrites(true);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.deleteGrant(9L));

		assertTrue(ex.getMessage().contains("冻结"));
		verifyNoInteractions(grantMapper);
	}

	@Test
	void modifyPolicyKeepsOriginalBehaviorWhenFreezeDisabledByDefault() {
		when(dataAgentService.requireAgent(AGENT_ID)).thenReturn(DataAgent.builder().id(AGENT_ID).build());
		when(policyMapper.findByAgentId(AGENT_ID)).thenReturn(null);

		assertDoesNotThrow(() -> service.modifyPolicy(AGENT_ID, validPolicyReq()));

		// 无既有策略时按原路径走默认策略落库（insert），冻结开关默认 false 零行为变化。
		verify(policyMapper).insert(any(DataAgentVisibilityPolicy.class));
	}

	/**
	 * 构造合法可见性策略请求（覆盖 applyPolicyRequest 的全部必填枚举校验）。
	 */
	private AgentVisibilityPolicyReq validPolicyReq() {
		AgentVisibilityPolicyReq request = new AgentVisibilityPolicyReq();
		request.setAgentId(AGENT_ID);
		request.setConversationScope(AgentVisibilityConstant.CONVERSATION_SCOPE_TENANT);
		request.setCatalogScope(AgentVisibilityConstant.CATALOG_SCOPE_TENANT);
		request.setApplyMode(AgentVisibilityConstant.APPLY_MODE_APPROVAL_REQUIRED);
		request.setApprovalMode(AgentVisibilityConstant.APPROVAL_MODE_LOCAL);
		request.setStatus(AgentVisibilityConstant.POLICY_STATUS_ENABLED);
		return request;
	}

}
