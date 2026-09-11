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
package com.sn68.agent.dataagent.service.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteDependencyValueType;
import com.sn68.agent.dataagent.routing.model.RouteStepInputMapping;
import com.sn68.agent.dataagent.routing.model.RouteStepOutputBinding;
import com.sn68.agent.dataagent.routing.v2.PlanCompileException;
import com.sn68.agent.dataagent.routing.v2.PlanCompiler;
import com.sn68.agent.dataagent.routing.v2.ProposalForbiddenFieldGuard;
import com.sn68.agent.dataagent.routing.v2.RouteProposalV2Parser;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlan;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.service.CompiledPlanPersistenceService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * durable run 开跑前 ACTIVE 计划闸门：生产路径至少一次 save()，PortSpec 缺口记录 reasonCode，
 * 失败关闭，且绝不走 saveShadow。
 */
class DurableCompiledPlanActivatorTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private CompiledPlanPersistenceService persistence;

	private AgentRuntimePlanMapper planMapper;

	private AgentRuntimeRunMapper runMapper;

	private DurableCompiledPlanActivator activator;

	@BeforeEach
	void setUp() {
		persistence = mock(CompiledPlanPersistenceService.class);
		planMapper = mock(AgentRuntimePlanMapper.class);
		runMapper = mock(AgentRuntimeRunMapper.class);
		PlanCompiler compiler = new PlanCompiler(new RouteProposalV2Parser(OBJECT_MAPPER),
				new ProposalForbiddenFieldGuard(), OBJECT_MAPPER);
		activator = new DurableCompiledPlanActivator(compiler, persistence, planMapper, runMapper);
	}

	@Test
	void savesActiveCompiledPlanFromV1Routes() {
		stubFreshRun();
		when(persistence.save(eq(77L), any(CompiledPlan.class)))
			.thenReturn(AgentRuntimePlan.builder().id(501L).status("ACTIVE").build());

		activator.requireActivePlan(77L, legacyRun(), List.of(route("s1", 1L, List.of(), List.of(), List.of()),
				route("s2", 2L, List.of("s1"), List.of(), List.of())));

		CompiledPlan saved = capturedSave();
		assertEquals(2, saved.steps().size());
		assertEquals("s1", saved.steps().get(0).stepKey());
		assertEquals("s2", saved.steps().get(1).stepKey());
		assertTrue(saved.policySnapshot().contains("LEGACY_ORCHESTRATION"));
		verify(persistence, never()).saveShadow(anyLong(), any(CompiledPlan.class));
	}

	@Test
	void portSpecGapRecordsReasonCodeAndPersistsControlDagWithoutPretendingBindingsCompiled() {
		stubFreshRun();
		when(persistence.save(eq(77L), any(CompiledPlan.class)))
			.thenReturn(AgentRuntimePlan.builder().id(501L).status("ACTIVE").build());
		CollaboratorRoute source = route("s1", 1L, List.of(),
				List.of(new RouteStepOutputBinding("customerId", RouteDependencyValueType.ID)), List.of());
		CollaboratorRoute dependent = route("s2", 2L, List.of("s1"), List.of(),
				List.of(new RouteStepInputMapping("s1", "customerId", "customer", RouteDependencyValueType.ID)));

		activator.requireActivePlan(77L, legacyRun(), List.of(source, dependent));

		CompiledPlan saved = capturedSave();
		assertTrue(saved.policySnapshot().contains(PlanCompileException.PORT_NOT_FOUND));
		assertTrue(saved.steps().stream().allMatch(step -> step.bindings().isEmpty()));
		assertEquals(List.of("s1"), saved.steps().get(1).dependsOn());
		verify(persistence, never()).saveShadow(anyLong(), any(CompiledPlan.class));
	}

	@Test
	void skipsCompileWhenActivePlanAlreadyExists() {
		when(planMapper.findActiveByRunId(77L)).thenReturn(AgentRuntimePlan.builder().id(9L).status("ACTIVE").build());

		activator.requireActivePlan(77L, legacyRun(), List.of(route("s1", 1L, List.of(), List.of(), List.of())));

		verify(persistence, never()).save(anyLong(), any(CompiledPlan.class));
		verify(persistence, never()).saveShadow(anyLong(), any(CompiledPlan.class));
		verify(runMapper, never()).selectById(anyLong());
	}

	@Test
	void saveFailureIsNotSwallowed() {
		stubFreshRun();
		when(persistence.save(eq(77L), any(CompiledPlan.class))).thenThrow(CheckedException.fail("insert failed"));

		CheckedException ex = assertThrows(CheckedException.class, () -> activator.requireActivePlan(77L, legacyRun(),
				List.of(route("s1", 1L, List.of(), List.of(), List.of()))));
		assertTrue(ex.getMessage().contains("insert failed"));
	}

	@Test
	void missingTenantFailsClosedWithoutSave() {
		AgentRuntimeRun run = runtimeRun();
		run.setTenantId(null);
		when(planMapper.findActiveByRunId(77L)).thenReturn(null);
		when(runMapper.selectById(77L)).thenReturn(run);

		CheckedException ex = assertThrows(CheckedException.class, () -> activator.requireActivePlan(77L, legacyRun(),
				List.of(route("s1", 1L, List.of(), List.of(), List.of()))));
		assertTrue(ex.getMessage().contains("CONTEXT_TENANT_MISSING"));
		verify(persistence, never()).save(anyLong(), any(CompiledPlan.class));
	}

	@Test
	void emptyRoutesFailClosed() {
		stubFreshRun();

		CheckedException ex = assertThrows(CheckedException.class,
				() -> activator.requireActivePlan(77L, legacyRun(), List.of()));
		assertTrue(ex.getMessage().contains("EMPTY_ROUTES"));
		verify(persistence, never()).save(anyLong(), any(CompiledPlan.class));
	}

	@Test
	void conversionSkipFailsClosedWithReasonCodeAndDoesNotSave() {
		AgentRuntimeRun run = runtimeRun();
		run.setQuery(null);
		when(planMapper.findActiveByRunId(77L)).thenReturn(null);
		when(runMapper.selectById(77L)).thenReturn(run);
		CollaboratorRoute blankTask = new CollaboratorRoute(
				AgentCollaborator.builder().id(1L).collaboratorAgentId(11L).enabled(true).build(),
				DataAgent.builder().id(11L).name("协作者").build(), " ", "MODEL_SELECT", "返回结果", "s1", List.of(),
				List.of(), List.of(), DelegationMode.AUTO_READ_ONLY.name());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> activator.requireActivePlan(77L, AgentOrchestrationRun.builder().id(100L).build(),
						List.of(blankTask)));
		assertTrue(ex.getMessage().contains("TASK_TEXT_MISSING"));
		verify(persistence, never()).save(anyLong(), any(CompiledPlan.class));
		verify(persistence, never()).saveShadow(anyLong(), any(CompiledPlan.class));
	}

	private void stubFreshRun() {
		when(planMapper.findActiveByRunId(77L)).thenReturn(null);
		when(runMapper.selectById(77L)).thenReturn(runtimeRun());
	}

	private CompiledPlan capturedSave() {
		ArgumentCaptor<CompiledPlan> captor = ArgumentCaptor.forClass(CompiledPlan.class);
		verify(persistence).save(eq(77L), captor.capture());
		return captor.getValue();
	}

	private static AgentRuntimeRun runtimeRun() {
		AgentRuntimeRun run = AgentRuntimeRun.builder()
			.tenantId("7")
			.agentId(9L)
			.ownerId(100L)
			.releaseId(0L)
			.runtimeRequestId("req-1")
			.query("查客户并下单")
			.deadlineAt(Instant.now().plusSeconds(60))
			.build();
		run.setId(77L);
		return run;
	}

	private static AgentOrchestrationRun legacyRun() {
		return AgentOrchestrationRun.builder().id(100L).query("查客户并下单").build();
	}

	private static CollaboratorRoute route(String stepId, Long collaboratorId, List<String> dependsOn,
			List<RouteStepOutputBinding> outputs, List<RouteStepInputMapping> inputs) {
		AgentCollaborator collaborator = AgentCollaborator.builder()
			.id(collaboratorId)
			.collaboratorAgentId(collaboratorId + 10)
			.roleName("角色")
			.delegationMode(DelegationMode.AUTO_READ_ONLY.name())
			.enabled(true)
			.build();
		DataAgent dataAgent = DataAgent.builder().id(collaboratorId + 10).name("协作者" + collaboratorId).build();
		return new CollaboratorRoute(collaborator, dataAgent, "任务" + stepId, "MODEL_MULTI_SELECTED", "返回结果",
				stepId, dependsOn, outputs, inputs, DelegationMode.AUTO_READ_ONLY.name());
	}

}
