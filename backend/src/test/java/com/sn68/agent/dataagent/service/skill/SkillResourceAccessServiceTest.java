/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.Test;

class SkillResourceAccessServiceTest {

	private final DataAgentSkillMapper skillMapper = org.mockito.Mockito.mock(DataAgentSkillMapper.class);

	private final SkillManagementTenantService managementTenantService =
			org.mockito.Mockito.mock(SkillManagementTenantService.class);

	private final SkillResourceAccessService service = new SkillResourceAccessService(skillMapper,
			managementTenantService);

	@Test
	void queryDatasourceResourcesRequireDeterministicOrReactQuerySkill() {
		stubSkill("QUERY", "REACT");

		assertDoesNotThrow(() -> service.requireQueryResourceSkill(1L));

		stubSkill("QA", "KNOWLEDGE");
		assertThrows(CheckedException.class, () -> service.requireQueryResourceSkill(1L));
	}

	@Test
	void businessKnowledgeResourcesAllowOnlyQueryAndFlowSkills() {
		stubSkill("QUERY", "DETERMINISTIC");
		assertDoesNotThrow(() -> service.requireBusinessKnowledgeResourceSkill(1L));

		stubSkill("QA", "KNOWLEDGE");
		assertThrows(CheckedException.class, () -> service.requireBusinessKnowledgeResourceSkill(1L));

		stubSkill("ACTION", "FLOW");
		assertDoesNotThrow(() -> service.requireBusinessKnowledgeResourceSkill(1L));

		stubSkill("ORCHESTRATION", "FLOW");
		assertDoesNotThrow(() -> service.requireBusinessKnowledgeResourceSkill(1L));
	}

	@Test
	void knowledgeBaseResourcesRequireQaKnowledgeSkill() {
		stubSkill("QA", "KNOWLEDGE");
		assertDoesNotThrow(() -> service.requireKnowledgeBaseResourceSkill(1L));

		stubSkill("QUERY", "REACT");
		assertThrows(CheckedException.class, () -> service.requireKnowledgeBaseResourceSkill(1L));
	}

	private void stubSkill(String skillKind, String executionMode) {
		when(skillMapper.selectById(1L)).thenReturn(DataAgentSkill.builder()
			.id(1L)
			.tenantId("1")
			.scope("TENANT")
			.skillKind(skillKind)
			.executionMode(executionMode)
			.deleted(false)
			.build());
		when(managementTenantService.effectiveTenantId()).thenReturn("1");
	}

}
