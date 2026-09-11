/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2;

import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityCandidateSet;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityDescriptor;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityRiskLevel;
import com.sn68.agent.dataagent.routing.v2.model.PortCardinality;
import com.sn68.agent.dataagent.routing.v2.model.PortSensitivity;
import com.sn68.agent.dataagent.routing.v2.model.PortSpec;
import com.sn68.agent.dataagent.routing.v2.model.RoutePortValueType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RouteCandidateCapabilityAdapter 只读适配测试:V1 候选 → V2 候选能力集,不改动 V1 类。
 */
class RouteCandidateCapabilityAdapterTest {

	@Test
	void adaptsCandidateListToOpaqueHandles() {
		CapabilityCandidateSet set = RouteCandidateCapabilityAdapter.toCandidateSet(List.of(
				candidate(RouteTargetType.SKILL, 5L, 50L, 500L, RouteRisk.READ_ONLY),
				candidate(RouteTargetType.COLLABORATOR, 6L, null, null, RouteRisk.WRITE)));

		assertEquals(2, set.capabilities().size());
		CapabilityDescriptor first = set.find("cap-1");
		assertEquals(CapabilityRiskLevel.READ_ONLY, first.riskLevel());
		assertEquals("SKILL", first.versionRef().capabilityKind());
		assertEquals(5L, first.versionRef().capabilityId());
		assertEquals(50L, first.versionRef().versionId());
		assertEquals(500L, first.versionRef().executionRefId());
		assertTrue(first.inputPorts().isEmpty());
		assertFalse(first.orchestration());
		CapabilityDescriptor second = set.find("cap-2");
		assertEquals(CapabilityRiskLevel.WRITE, second.riskLevel());
		assertEquals("COLLABORATOR", second.versionRef().capabilityKind());
	}

	@Test
	void adaptsEmptyOrNullListToEmptySet() {
		assertTrue(RouteCandidateCapabilityAdapter.toCandidateSet(null).isEmpty());
		assertTrue(RouteCandidateCapabilityAdapter.toCandidateSet(List.of()).isEmpty());
	}

	@Test
	void buildsDescriptorWithProvidedPorts() {
		PortSpec input = new PortSpec("keyword", RoutePortValueType.FILTER, true, PortCardinality.SINGLE,
				PortSensitivity.PUBLIC);
		PortSpec output = new PortSpec("customerRef", RoutePortValueType.ID, false, PortCardinality.SINGLE,
				PortSensitivity.INTERNAL);

		CapabilityDescriptor descriptor = RouteCandidateCapabilityAdapter.toDescriptor("cap-lookup",
				candidate(RouteTargetType.SKILL, 5L, 50L, 500L, RouteRisk.READ_ONLY), List.of(input), List.of(output));

		assertEquals("cap-lookup", descriptor.capabilityHandle());
		assertEquals(input, descriptor.inputPort("keyword"));
		assertEquals(output, descriptor.outputPort("customerRef"));
	}

	@Test
	void mapsAllRiskLevelsConservatively() {
		assertEquals(CapabilityRiskLevel.READ_ONLY, RouteCandidateCapabilityAdapter.toRiskLevel(RouteRisk.READ_ONLY));
		assertEquals(CapabilityRiskLevel.WRITE, RouteCandidateCapabilityAdapter.toRiskLevel(RouteRisk.WRITE));
		assertEquals(CapabilityRiskLevel.FLOW, RouteCandidateCapabilityAdapter.toRiskLevel(RouteRisk.FLOW));
		assertEquals(CapabilityRiskLevel.DELEGATED, RouteCandidateCapabilityAdapter.toRiskLevel(RouteRisk.DELEGATED));
		assertEquals(CapabilityRiskLevel.UNKNOWN, RouteCandidateCapabilityAdapter.toRiskLevel(RouteRisk.UNKNOWN));
		assertEquals(CapabilityRiskLevel.UNKNOWN, RouteCandidateCapabilityAdapter.toRiskLevel(null));
	}

	@Test
	void rejectsNullCandidate() {
		assertThrows(IllegalArgumentException.class,
				() -> RouteCandidateCapabilityAdapter.toDescriptor("cap-x", null, List.of(), List.of()));
	}

	private static RouteCandidate candidate(RouteTargetType targetType, Long targetId, Long versionId,
			Long executionRefId, RouteRisk risk) {
		return new RouteCandidate(new RouteTargetRef(targetType, targetId, versionId, executionRefId), "tenant-1", 9L,
				"客户查询", "按名称查询客户", "QUERY", "REACT", RouteRules.empty(), risk, 10, 1L, 2L, "checksum",
				"fingerprint", null);
	}

}
