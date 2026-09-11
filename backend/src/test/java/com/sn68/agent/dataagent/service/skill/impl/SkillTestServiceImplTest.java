/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.skill.SkillDetailResp;
import com.sn68.agent.dataagent.dto.skill.SkillFlowTestResult;
import com.sn68.agent.dataagent.dto.skill.SkillRouteTestReq;
import com.sn68.agent.dataagent.dto.routing.RouteRulesDTO;
import com.sn68.agent.dataagent.flow.FlowDefinitionValidator;
import com.sn68.agent.dataagent.routing.RouteRiskResolver;
import com.sn68.agent.dataagent.routing.RouteScorer;
import com.sn68.agent.dataagent.routing.RouteTextNormalizer;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.service.skill.SkillCatalogService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillTestServiceImplTest {

	private final SkillCatalogService catalogService = mock(SkillCatalogService.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final RouteTextNormalizer normalizer = new RouteTextNormalizer();

	private final RouteRiskResolver riskResolver = mock(RouteRiskResolver.class);

	private final SkillTestServiceImpl service = new SkillTestServiceImpl(catalogService,
			new RouteScorer(normalizer), riskResolver,
			new FlowDefinitionValidator(objectMapper), objectMapper);

	@Test
	void testsCurrentDraftRouteRules() {
		when(catalogService.detail("demand-create")).thenReturn(detail("FLOW",
				Map.of("exact", List.of("我要下单")), flowDefinition()));

		var result = service.testRoute("demand-create", new SkillRouteTestReq("我要下单"));

		assertTrue(result.matched());
		assertEquals("EXACT", result.reasonCode());
	}

	@Test
	void routeTestUsesResolvedFlowRisk() {
		when(catalogService.detail("demand-create")).thenReturn(detail("FLOW", Map.of(), flowDefinition()));
		when(riskResolver.resolveSkill(org.mockito.ArgumentMatchers.any())).thenReturn(RouteRisk.FLOW);

		service.testRoute("demand-create", new SkillRouteTestReq("unknown"));

		verify(riskResolver).resolveSkill(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void dryRunBlocksWriteNodeWithoutExecutingRuntime() {
		when(catalogService.detail("demand-create")).thenReturn(detail("FLOW", Map.of(), flowDefinition()));

		SkillFlowTestResult result = service.testFlow("demand-create");

		assertTrue(result.valid());
		assertEquals("BLOCKED_WRITE", result.nodes().stream()
			.filter(node -> "execute".equals(node.nodeId()))
			.findFirst()
			.orElseThrow()
			.behavior());
	}

	private SkillDetailResp detail(String mode, Map<String, Object> routeRules, Map<String, Object> flowDefinition) {
		SkillDetailResp.Version version = new SkillDetailResp.Version(10L, 1, "DRAFT", "ACTION", mode, "",
				routeRules(routeRules), Map.of(), Map.of(), flowDefinition, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
				Map.of(), Map.of(), Map.of(), Map.of(), null, null);
		return new SkillDetailResp(1L, null, "demand-create", "需求单创建", "", "", "PLATFORM", "ACTION", mode,
				"DRAFT", 0, 10L, null, version, List.of());
	}

	private RouteRulesDTO routeRules(Map<String, Object> values) {
		return new RouteRulesDTO(strings(values, "exact"), strings(values, "phrases"), strings(values, "aliases"),
				strings(values, "positiveExamples"), strings(values, "positivePatterns"),
				strings(values, "negativeExamples"), strings(values, "hardExcludes"),
				strings(values, "hardExcludePatterns"), Boolean.TRUE.equals(values.get("allowFlowAutoSelect")));
	}

	private List<String> strings(Map<String, Object> values, String field) {
		Object value = values.get(field);
		if (!(value instanceof List<?> items)) {
			return List.of();
		}
		return items.stream().map(String.class::cast).toList();
	}

	private Map<String, Object> flowDefinition() {
		return Map.of("schemaVersion", "skill-flow/v1", "startNode", "confirm", "nodes",
				List.of(Map.of("id", "confirm", "type", "confirm", "next", "execute"),
						Map.of("id", "execute", "type", "execute", "next", "end", "config",
								Map.of("resourceVersionId", 9, "resultQuery", Map.of("resourceVersionId", 10,
										"completedCondition", Map.of("op", "eq", "path", "/result/found", "value", true)))),
						Map.of("id", "end", "type", "end")));
	}

}
