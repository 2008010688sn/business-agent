/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.agent.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.routing.RouteStageException;
import com.sn68.agent.dataagent.routing.model.RouteDependencyValueType;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RouteStepInputMapping;
import com.sn68.agent.dataagent.routing.model.RouteStepOutputBinding;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollaboratorDependencyContractServiceTest {

	private static final String TENANT_ID = "tenant-1";

	private static final Long SOURCE_AGENT_ID = 10L;

	private static final Long TARGET_AGENT_ID = 20L;

	private static final RouteTargetRef SOURCE_TARGET = new RouteTargetRef(RouteTargetType.COLLABORATOR, 101L,
			null, SOURCE_AGENT_ID);

	private static final RouteTargetRef TARGET_TARGET = new RouteTargetRef(RouteTargetType.COLLABORATOR, 102L,
			null, TARGET_AGENT_ID);

	@Test
	void bindsPublishedExplicitSourceMapping() {
		Fixture fixture = fixture(outputSchema("customerIds", "ID"),
				inputSchema("customerIds", "ID", "s1.customerIds"));

		RoutePlan bound = fixture.service().bind(dependentPlan(), fixture.collaborators(), TENANT_ID);

		RoutePlanStep source = bound.steps().get(0);
		RoutePlanStep target = bound.steps().get(1);
		assertEquals(List.of(new RouteStepOutputBinding("customerIds", RouteDependencyValueType.ID)),
				source.outputBindings());
		assertEquals(List.of(new RouteStepInputMapping("s1", "customerIds", "customerIds",
				RouteDependencyValueType.ID)), target.inputMappings());
	}

	@Test
	void rejectsExplicitMappingToUndeclaredPredecessor() {
		Fixture fixture = fixture(outputSchema("customerIds", "ID"),
				inputSchema("customerIds", "ID", "s9.customerIds"));

		RouteStageException error = assertThrows(RouteStageException.class,
				() -> fixture.service().bind(dependentPlan(), fixture.collaborators(), TENANT_ID));

		assertEquals("ROUTE_PLAN_DEPENDENCY_CONTRACT_INVALID", error.reasonCode());
		assertTrue(error.getMessage().contains("invalid predecessor"));
	}

	@Test
	void rejectsExplicitMappingToUndeclaredPredecessorField() {
		Fixture fixture = fixture(outputSchema("customerIds", "ID"),
				inputSchema("customerIds", "ID", "s1.orderIds"));

		RouteStageException error = assertThrows(RouteStageException.class,
				() -> fixture.service().bind(dependentPlan(), fixture.collaborators(), TENANT_ID));

		assertEquals("ROUTE_PLAN_DEPENDENCY_CONTRACT_INVALID", error.reasonCode());
		assertTrue(error.getMessage().contains("does not match a predecessor output"));
	}

	@Test
	void dependentStepWithoutPublishedInputKeepsOrderingOnly() {
		Fixture fixture = fixture(outputSchema("customerIds", "ID"), "{\"properties\":{}}");

		RoutePlan bound = fixture.service().bind(dependentPlan(), fixture.collaborators(), TENANT_ID);

		assertEquals(List.of(), bound.steps().get(1).inputMappings());
		assertEquals(List.of("s1"), bound.steps().get(1).dependsOn());
	}

	@Test
	void rejectsNonJsonPredecessorOutput() {
		Fixture fixture = fixture(outputSchema("customerIds", "ID"), inputSchema("customerIds", "ID", "s1.customerIds"));
		CollaboratorRoute source = route("s1", List.of(),
				List.of(new RouteStepOutputBinding("customerIds", RouteDependencyValueType.ID)), List.of());

		RouteStageException error = assertThrows(RouteStageException.class,
				() -> fixture.service().extractOutput(source, "customerIds are 101 and 102"));

		assertEquals("ROUTE_PLAN_DEPENDENCY_CONTRACT_INVALID", error.reasonCode());
		assertTrue(error.getMessage().contains("does not satisfy the published output contract"));
	}

	@Test
	void restoresDependencyInputsFromPersistedStructuredPredecessorOutput() {
		Fixture fixture = fixture(outputSchema("customerIds", "ID"), inputSchema("customerIds", "ID", "s1.customerIds"));
		CollaboratorRoute source = route("s1", List.of(),
				List.of(new RouteStepOutputBinding("customerIds", RouteDependencyValueType.ID)), List.of());
		CollaboratorRoute target = route("s2", List.of("s1"), List.of(),
				List.of(new RouteStepInputMapping("s1", "customerIds", "customerIds", RouteDependencyValueType.ID)));
		Map<String, Object> persistedOutput = fixture.service().extractOutput(source, "{\"customerIds\":[101,102]}");
		CollaboratorExecutionResult restored = new CollaboratorExecutionResult(source, "persisted answer", null, 12L,
				null, null, persistedOutput);

		Map<String, Object> inputs = fixture.service().dependencyInputs(target, List.of(restored));

		assertEquals(List.of(101, 102), inputs.get("customerIds"));
	}

	private RoutePlan dependentPlan() {
		return new RoutePlan(List.of(new RoutePlanStep("s1", SOURCE_TARGET, "find customers", List.of(),
				"customer identifiers"), new RoutePlanStep("s2", TARGET_TARGET, "calculate receivables", List.of("s1"),
				"receivable summary")));
	}

	private CollaboratorRoute route(String stepId, List<String> dependsOn, List<RouteStepOutputBinding> outputBindings,
			List<RouteStepInputMapping> inputMappings) {
		DataAgent collaborator = "s1".equals(stepId) ? sourceAgent() : targetAgent();
		return new CollaboratorRoute(null, collaborator, "task", "matched", "output", stepId, dependsOn,
				outputBindings, inputMappings);
	}

	private Fixture fixture(String sourceOutputSchema, String targetInputSchema) {
		DataAgentSkillBindingMapper bindingMapper = mock(DataAgentSkillBindingMapper.class);
		DataAgentSkillMapper skillMapper = mock(DataAgentSkillMapper.class);
		DataAgentSkillVersionMapper versionMapper = mock(DataAgentSkillVersionMapper.class);
		DataAgentSkill sourceSkill = skill(30L);
		DataAgentSkill targetSkill = skill(40L);
		DataAgentSkillVersion sourceVersion = version(50L, sourceSkill.getId(), sourceOutputSchema, null);
		DataAgentSkillVersion targetVersion = version(60L, targetSkill.getId(), null, targetInputSchema);
		when(bindingMapper.findEnabledByAgentId(SOURCE_AGENT_ID, TENANT_ID))
			.thenReturn(List.of(binding(SOURCE_AGENT_ID, sourceSkill.getId(), sourceVersion.getId())));
		when(bindingMapper.findEnabledByAgentId(TARGET_AGENT_ID, TENANT_ID))
			.thenReturn(List.of(binding(TARGET_AGENT_ID, targetSkill.getId(), targetVersion.getId())));
		when(skillMapper.selectBatchIds(any())).thenReturn(List.of(sourceSkill, targetSkill));
		when(versionMapper.selectBatchIds(any())).thenReturn(List.of(sourceVersion, targetVersion));
		return new Fixture(new CollaboratorDependencyContractService(bindingMapper, skillMapper, versionMapper,
				new ObjectMapper()), Map.of(SOURCE_TARGET, sourceAgent(), TARGET_TARGET, targetAgent()));
	}

	private DataAgent sourceAgent() {
		return DataAgent.builder().id(SOURCE_AGENT_ID).tenantId(TENANT_ID).status("published").build();
	}

	private DataAgent targetAgent() {
		return DataAgent.builder().id(TARGET_AGENT_ID).tenantId(TENANT_ID).status("published").build();
	}

	private DataAgentSkillBinding binding(Long agentId, Long skillId, Long versionId) {
		return DataAgentSkillBinding.builder().agentId(agentId).tenantId(TENANT_ID).skillId(skillId)
			.pinnedSkillVersionId(versionId).enabled(true).deleted(false).build();
	}

	private DataAgentSkill skill(Long id) {
		return DataAgentSkill.builder().id(id).tenantId(TENANT_ID).status("PUBLISHED").deleted(false).build();
	}

	private DataAgentSkillVersion version(Long id, Long skillId, String outputSchema, String inputSchema) {
		return DataAgentSkillVersion.builder().id(id).tenantId(TENANT_ID).skillId(skillId).status("PUBLISHED")
			.outputSchema(outputSchema).inputSchema(inputSchema).deleted(false).build();
	}

	private String outputSchema(String field, String valueType) {
		return "{\"properties\":{\"%s\":{\"x-orchestration-transfer\":\"%s\"}}}".formatted(field, valueType);
	}

	private String inputSchema(String field, String valueType, String source) {
		return ("{\"properties\":{\"%s\":{\"x-orchestration-transfer\":\"%s\","
				+ "\"x-orchestration-source\":\"%s\"}}}").formatted(field, valueType, source);
	}

	private record Fixture(CollaboratorDependencyContractService service, Map<RouteTargetRef, DataAgent> collaborators) {
	}

}
