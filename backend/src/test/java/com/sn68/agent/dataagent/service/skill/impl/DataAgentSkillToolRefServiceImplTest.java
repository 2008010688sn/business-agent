/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.skill.AgentSkillToolRefDTO;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.service.skill.SkillCatalogService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataAgentSkillToolRefServiceImplTest {

	private final DataAgentSkillToolRefMapper mapper = mock(DataAgentSkillToolRefMapper.class);

	private final DataAgentSkillVersionMapper skillVersionMapper = mock(DataAgentSkillVersionMapper.class);

	private final AgentExecutionResourceVersionMapper resourceVersionMapper =
			mock(AgentExecutionResourceVersionMapper.class);

	private final SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);

	private final DataAgentSkillToolRefServiceImpl service = new DataAgentSkillToolRefServiceImpl(mapper,
			skillVersionMapper, resourceVersionMapper, skillCatalogService, new ObjectMapper());

	@Test
	void replaceDerivesResourceKeyAndPreservesServerOwnedFields() {
		DataAgentSkill skill = skill("REACT");
		DataAgentSkillToolRef existing = DataAgentSkillToolRef.builder()
			.skillVersionId(10L)
			.resourceVersionId(99L)
			.resourceKey("tool.read")
			.status("disabled")
			.extConfig("{\"confirmation\":true}")
			.build();
		when(skillCatalogService.requireManageable("sample-skill")).thenReturn(skill);
		when(skillVersionMapper.selectById(10L)).thenReturn(draft());
		when(resourceVersionMapper.selectList(any())).thenReturn(List.of(resource(99L, "READ", "MODEL")));
		when(mapper.findBySkillVersionId(10L)).thenReturn(List.of(existing));
		AgentSkillToolRefDTO request = new AgentSkillToolRefDTO(null, null, null, "spoofed.key", 99L,
				"new usage", "enabled", 20, Map.of("confirmation", false));

		service.saveRefs("sample-skill", List.of(request));

		ArgumentCaptor<DataAgentSkillToolRef> captor = ArgumentCaptor.forClass(DataAgentSkillToolRef.class);
		verify(mapper).insert(captor.capture());
		DataAgentSkillToolRef saved = captor.getValue();
		assertEquals("tool.read", saved.getResourceKey());
		assertEquals("disabled", saved.getStatus());
		assertEquals("{\"confirmation\":true}", saved.getExtConfig());
		assertEquals("new usage", saved.getUsage());
	}

	@Test
	void invalidModeDoesNotDeleteExistingReferences() {
		when(skillCatalogService.requireManageable("sample-skill")).thenReturn(skill("REACT"));
		when(skillVersionMapper.selectById(10L)).thenReturn(draft());
		when(resourceVersionMapper.selectList(any())).thenReturn(List.of(resource(99L, "WRITE", "FLOW_ONLY")));
		when(mapper.findBySkillVersionId(10L)).thenReturn(List.of());
		AgentSkillToolRefDTO request = new AgentSkillToolRefDTO(null, null, null, null, 99L,
				"write", null, 0, null);

		assertThrows(CheckedException.class, () -> service.saveRefs("sample-skill", List.of(request)));

		verify(mapper, never()).deleteBySkillVersionId(10L);
	}

	private DataAgentSkill skill(String mode) {
		return DataAgentSkill.builder().id(1L).tenantId("tenant-1").skillCode("sample-skill").scope("TENANT")
			.executionMode(mode).latestDraftVersionId(10L).build();
	}

	private DataAgentSkillVersion draft() {
		return DataAgentSkillVersion.builder().id(10L).skillId(1L).skillKind("QUERY")
			.executionMode("REACT").status("DRAFT").build();
	}

	private AgentExecutionResourceVersion resource(Long id, String accessMode, String exposureMode) {
		return AgentExecutionResourceVersion.builder().id(id).resourceKey("tool.read").status("PUBLISHED")
			.accessMode(accessMode).exposureMode(exposureMode).build();
	}

}
