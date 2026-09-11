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
package com.sn68.agent.dataagent.service.agent;

import com.sn68.agent.dataagent.constant.AgentStatusConstant;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.dto.agent.AgentCollaboratorSaveReq;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.repository.AgentCollaboratorMapper;
import com.sn68.agent.dataagent.routing.CollaboratorCapabilityResolver;
import com.sn68.agent.dataagent.routing.CollaboratorCapabilityResolver.CollaboratorCapability;
import com.sn68.agent.dataagent.routing.RouteRulesService;
import com.sn68.agent.dataagent.routing.RouteTextNormalizer;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.service.routing.RouteArtifactService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataAgentCollaboratorServiceImplTest {

	private final AgentCollaboratorMapper collaboratorMapper = mock(AgentCollaboratorMapper.class);

	private final DataAgentService agentService = mock(DataAgentService.class);

	private final RouteRulesService routeRulesService =
			new RouteRulesService(new ObjectMapper(), new RouteTextNormalizer());

        private final RouteArtifactService routeArtifactService = mock(RouteArtifactService.class);

        private final CollaboratorCapabilityResolver capabilityResolver = mock(CollaboratorCapabilityResolver.class);

	private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

        private final AgentCollaboratorServiceImpl service = new AgentCollaboratorServiceImpl(collaboratorMapper,
                        agentService, routeRulesService, routeArtifactService, capabilityResolver, transactionTemplate);

	@Test
	void requireEnabled_rejectsSelfCall() {
		when(collaboratorMapper.findActive(1L, 1L)).thenReturn(collaborator(1L, 1L));

		assertThrows(CheckedException.class, () -> service.requireEnabled(1L, 1L));
	}

	@Test
	void requireEnabled_rejectsAnotherOrchestrator() {
		when(collaboratorMapper.findActive(1L, 2L)).thenReturn(collaborator(1L, 2L));
		when(agentService.requireAgent(1L)).thenReturn(agent(1L, AgentTypeConstant.ORCHESTRATOR,
				AgentStatusConstant.PUBLISHED));
		when(agentService.requireAgent(2L)).thenReturn(agent(2L, AgentTypeConstant.ORCHESTRATOR,
				AgentStatusConstant.PUBLISHED));

		assertThrows(CheckedException.class, () -> service.requireEnabled(1L, 2L));
	}

	@Test
	void requireEnabled_rejectsUnpublishedCollaboratorAtRuntime() {
		when(collaboratorMapper.findActive(1L, 2L)).thenReturn(collaborator(1L, 2L));
		when(agentService.requireAgent(1L)).thenReturn(agent(1L, AgentTypeConstant.ORCHESTRATOR,
				AgentStatusConstant.PUBLISHED));
		when(agentService.requireAgent(2L))
			.thenReturn(agent(2L, AgentTypeConstant.DATA_ANALYSIS, AgentStatusConstant.DRAFT));

		assertThrows(CheckedException.class, () -> service.requireEnabled(1L, 2L));
	}

	@Test
	void createRejectsFlowAutoSelectForCollaboratorRules() {
		when(agentService.requireAgent(1L)).thenReturn(agent(1L, AgentTypeConstant.ORCHESTRATOR,
				AgentStatusConstant.PUBLISHED));
		when(agentService.requireAgent(2L)).thenReturn(agent(2L, AgentTypeConstant.DATA_ANALYSIS,
				AgentStatusConstant.PUBLISHED));
		when(collaboratorMapper.findActive(1L, 2L)).thenReturn(null);
		when(capabilityResolver.resolve(2L, "tenant-1"))
				.thenReturn(new CollaboratorCapability(RouteRisk.READ_ONLY, 1, false));

		assertThrows(CheckedException.class, () -> service.create(1L,
				new AgentCollaboratorSaveReq(null, 1L, 2L, "查询", "查询协作者",
						java.util.Map.of("allowFlowAutoSelect", true), 0, true)));
	}

	@Test
	void listExpandsCollaboratorRulesToTheCompleteResponseContract() {
		AgentCollaborator collaborator = AgentCollaborator.builder().id(3L).agentId(1L).collaboratorAgentId(2L)
				.routingRules(java.util.Map.of("positivePatterns", java.util.List.of("give*order"))).build();
		when(agentService.requireAgent(1L)).thenReturn(agent(1L, AgentTypeConstant.ORCHESTRATOR,
				AgentStatusConstant.PUBLISHED));
		when(collaboratorMapper.findByAgentId(1L)).thenReturn(java.util.List.of(collaborator));

		var result = service.list(1L).get(0).getRoutingRules();

		assertEquals(java.util.List.of("give*order"), result.positivePatterns());
		assertEquals(java.util.List.of(), result.hardExcludePatterns());
		assertFalse(result.allowFlowAutoSelect());
	}

	@Test
	void createPersistsPatternRulesAndReturnsTheCompleteResponseContract() {
		when(agentService.requireAgent(1L)).thenReturn(agent(1L, AgentTypeConstant.ORCHESTRATOR,
				AgentStatusConstant.PUBLISHED));
		DataAgent collaboratorAgent = agent(2L, AgentTypeConstant.DATA_ANALYSIS, AgentStatusConstant.PUBLISHED);
                when(agentService.requireAgent(2L)).thenReturn(collaboratorAgent);
                when(agentService.findById(2L)).thenReturn(collaboratorAgent);
                when(capabilityResolver.resolve(2L, "tenant-1"))
                                .thenReturn(new CollaboratorCapability(RouteRisk.READ_ONLY, 1, false));
		when(collaboratorMapper.findActive(1L, 2L)).thenReturn(null);
		AtomicReference<AgentCollaborator> persisted = new AtomicReference<>();
		org.mockito.Mockito.doAnswer(invocation -> {
			persisted.set(invocation.getArgument(0));
			return null;
		}).when(collaboratorMapper).insert(any(AgentCollaborator.class));
		when(collaboratorMapper.selectById(any())).thenAnswer(invocation -> persisted.get());
		when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(mock(TransactionStatus.class));
		});

		var result = service.create(1L, new AgentCollaboratorSaveReq(null, 1L, 2L, "下单", "需求单创建",
				Map.of("positivePatterns", List.of("给*下单"), "hardExcludePatterns", List.of("怎么*下单")), 0, true));

		assertEquals(List.of("给*下单"), persisted.get().getRoutingRules().get("positivePatterns"));
		assertEquals(List.of("怎么*下单"), result.getRoutingRules().hardExcludePatterns());
                assertFalse(result.getRoutingRules().allowFlowAutoSelect());
        }

        @Test
        void updatePersistsValidatedDelegationMode() {
                DataAgent owner = agent(1L, AgentTypeConstant.ORCHESTRATOR, AgentStatusConstant.PUBLISHED);
                DataAgent target = agent(2L, AgentTypeConstant.DATA_ANALYSIS, AgentStatusConstant.PUBLISHED);
                AgentCollaborator existing = AgentCollaborator.builder().id(3L).agentId(1L).collaboratorAgentId(2L)
                                .roleName("old role").capabilityDescription("old capability")
                                .delegationMode(DelegationMode.INTERACTIVE.name()).routingRules(Map.of()).priority(0)
                                .enabled(true).lastModifyTime(java.time.Instant.now()).build();
                when(collaboratorMapper.findByIdAndAgentId(3L, 1L)).thenReturn(existing);
                when(agentService.requireAgent(1L)).thenReturn(owner);
                when(agentService.requireAgent(2L)).thenReturn(target);
                when(capabilityResolver.resolve(2L, "tenant-1"))
                                .thenReturn(new CollaboratorCapability(RouteRisk.WRITE, 1, true));
                when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                        TransactionCallback<?> callback = invocation.getArgument(0);
                        return callback.doInTransaction(mock(TransactionStatus.class));
                });
                when(collaboratorMapper.selectById(3L)).thenReturn(existing);

                service.update(1L, 3L, new AgentCollaboratorSaveReq(3L, 1L, 2L, "new role", "create order",
                                Map.of(), 0, true, DelegationMode.PLAN_CONFIRM.name()));

                assertEquals(DelegationMode.PLAN_CONFIRM.name(), existing.getDelegationMode());
        }

	private AgentCollaborator collaborator(Long agentId, Long collaboratorAgentId) {
		return AgentCollaborator.builder()
			.agentId(agentId)
			.collaboratorAgentId(collaboratorAgentId)
			.enabled(true)
			.build();
	}

	private DataAgent agent(Long id, String agentType, String status) {
		return DataAgent.builder().id(id).tenantId("tenant-1").agentType(agentType).status(status).build();
	}

}
