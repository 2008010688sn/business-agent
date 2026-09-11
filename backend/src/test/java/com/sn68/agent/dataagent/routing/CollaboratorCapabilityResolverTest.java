/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.routing.CollaboratorCapabilityResolver.CollaboratorCapability;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollaboratorCapabilityResolverTest {

	@Test
	void resolvesOnlyPublishedTenantCapabilities() {
		Fixture fixture = fixture("PUBLISHED", RouteRisk.READ_ONLY);

		CollaboratorCapability capability = fixture.resolver().resolve(20L, "tenant-1");

		assertEquals(RouteRisk.READ_ONLY, capability.effectiveRisk());
		assertEquals(1, capability.publishedCapabilityCount());
		assertTrue(fixture.resolver().supports(DelegationMode.AUTO_READ_ONLY, capability));
	}

	@Test
	void rejectsDraftCapabilitiesAndRiskModeMismatches() {
		Fixture fixture = fixture("DRAFT", RouteRisk.WRITE);

		CollaboratorCapability capability = fixture.resolver().resolve(20L, "tenant-1");

		assertEquals(RouteRisk.UNKNOWN, capability.effectiveRisk());
		assertFalse(fixture.resolver().supports(DelegationMode.INTERACTIVE, capability));
		assertFalse(fixture.resolver().supports(DelegationMode.AUTO_READ_ONLY,
				new CollaboratorCapability(RouteRisk.WRITE, 1, true)));
		assertFalse(fixture.resolver().supports(DelegationMode.PLAN_CONFIRM,
				new CollaboratorCapability(RouteRisk.WRITE, 2, false)));
		assertTrue(fixture.resolver().supports(DelegationMode.PLAN_CONFIRM,
				new CollaboratorCapability(RouteRisk.WRITE, 1, true)));
	}

	private Fixture fixture(String versionStatus, RouteRisk risk) {
		DataAgentSkillBindingMapper bindingMapper = mock(DataAgentSkillBindingMapper.class);
		DataAgentSkillMapper skillMapper = mock(DataAgentSkillMapper.class);
		DataAgentSkillVersionMapper versionMapper = mock(DataAgentSkillVersionMapper.class);
		RouteRiskResolver riskResolver = mock(RouteRiskResolver.class);
		DataAgentSkillBinding binding = DataAgentSkillBinding.builder().agentId(20L).tenantId("tenant-1")
			.skillId(30L).pinnedSkillVersionId(40L).enabled(true).deleted(false).build();
		DataAgentSkill skill = DataAgentSkill.builder().id(30L).tenantId("tenant-1").scope("TENANT")
			.status("PUBLISHED").deleted(false).build();
		DataAgentSkillVersion version = DataAgentSkillVersion.builder().id(40L).tenantId("tenant-1").skillId(30L)
			.status(versionStatus).skillKind("QUERY").executionMode("REACT").deleted(false).build();
		when(bindingMapper.findEnabledByAgentId(20L, "tenant-1")).thenReturn(List.of(binding));
		when(skillMapper.selectBatchIds(any())).thenReturn(List.of(skill));
		when(versionMapper.selectBatchIds(any())).thenReturn(List.of(version));
		when(riskResolver.resolveSkill(version)).thenReturn(risk);
		return new Fixture(new CollaboratorCapabilityResolver(bindingMapper, skillMapper, versionMapper, riskResolver));
	}

	private record Fixture(CollaboratorCapabilityResolver resolver) {
	}

}
