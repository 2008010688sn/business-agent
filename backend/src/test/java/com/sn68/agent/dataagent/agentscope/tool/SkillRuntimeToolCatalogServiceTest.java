/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.skilltool.SkillBoundToolCatalogService;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRuntimeToolCatalogServiceTest {

	@Test
	void getToolCallbacks_usesOnlyTheRoutedSkillVersionSnapshotAndExplicitToolRefs() {
		SkillResourceToolProvider provider = mock(SkillResourceToolProvider.class);
		SkillBoundToolCatalogService explicitCatalog = mock(SkillBoundToolCatalogService.class);
		ToolCallback resourceCallback = mock(ToolCallback.class);
		ToolCallback explicitCallback = mock(ToolCallback.class);
		SkillRuntimeToolCatalogService service = new SkillRuntimeToolCatalogService(List.of(provider), explicitCatalog);
		AgentRequest request = request(7L, 8L);
		RouteSelection selection = selection(7L, 8L);
		DataAgentSkill skill = skill(7L);
		DataAgentSkillVersion version = version(7L, 8L);

		when(provider.getSkillToolCallbacks(any(SkillVersionResources.class)))
			.thenReturn(Map.of("datasource.skill.search", resourceCallback));
		when(explicitCatalog.getToolCallbacks("1", "query-skill", 8L))
			.thenReturn(Map.of("tool.read", explicitCallback));

		Map<String, ToolCallback> callbacks = service.getToolCallbacks(request, selection, skill, version);

		assertEquals(Map.of("datasource.skill.search", resourceCallback, "tool.read", explicitCallback), callbacks);
		verify(provider).getSkillToolCallbacks(request.getRoutedSkillResources());
		verify(explicitCatalog).getToolCallbacks("1", "query-skill", 8L);
	}

	@Test
	void getToolCallbacks_rejectsMismatchedOrMissingRoutedSnapshot() {
		SkillResourceToolProvider provider = mock(SkillResourceToolProvider.class);
		SkillBoundToolCatalogService explicitCatalog = mock(SkillBoundToolCatalogService.class);
		SkillRuntimeToolCatalogService service = new SkillRuntimeToolCatalogService(List.of(provider), explicitCatalog);
		AgentRequest request = request(7L, 9L);

		Map<String, ToolCallback> callbacks = service.getToolCallbacks(request, selection(7L, 8L), skill(7L),
				version(7L, 8L));

		assertTrue(callbacks.isEmpty());
		verify(provider, never()).getSkillToolCallbacks(any());
		verify(explicitCatalog, never()).getToolCallbacks(any(), any(), any());
	}

	private AgentRequest request(Long skillId, Long versionId) {
		return AgentRequest.builder()
			.agentId("1")
			.routedSkillId(skillId)
			.routedSkillVersionId(versionId)
			.routedSkillResources(new SkillVersionResources(skillId, versionId, null, List.of(), List.of(), Map.of()))
			.build();
	}

	private RouteSelection selection(Long skillId, Long versionId) {
		return new RouteSelection(new RouteTargetRef(RouteTargetType.SKILL, skillId, versionId, null), 11L, 12L,
				RouteRisk.READ_ONLY, "checksum");
	}

	private DataAgentSkill skill(Long skillId) {
		return DataAgentSkill.builder().id(skillId).skillCode("query-skill").build();
	}

	private DataAgentSkillVersion version(Long skillId, Long versionId) {
		return DataAgentSkillVersion.builder().id(versionId).skillId(skillId).build();
	}
}
