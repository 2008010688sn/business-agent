/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sn68.agent.dataagent.service.skill.DataAgentSkillToolRefService;
import com.sn68.agent.dataagent.service.skill.SkillCatalogService;
import com.sn68.agent.dataagent.service.skill.SkillImportExportService;
import com.sn68.agent.dataagent.service.skill.SkillPublishService;
import com.sn68.agent.dataagent.service.skill.SkillTestService;
import com.sn68.agent.dataagent.service.skill.SkillValidationService;
import com.sn68.agent.framework.boot.response.GlobalResponseBodyAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SkillControllerTest {

	private final SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);

	private final SkillPublishService skillPublishService = mock(SkillPublishService.class);

	private final SkillValidationService skillValidationService = mock(SkillValidationService.class);

	private final SkillImportExportService skillImportExportService = mock(SkillImportExportService.class);

	private final DataAgentSkillToolRefService skillToolRefService = mock(DataAgentSkillToolRefService.class);

	private final SkillTestService skillTestService = mock(SkillTestService.class);

	private final MockMvc mockMvc = MockMvcBuilders
		.standaloneSetup(new SkillController(skillCatalogService, skillPublishService, skillValidationService,
				skillImportExportService, skillToolRefService, skillTestService))
		.setControllerAdvice(new GlobalResponseBodyAdvice())
		.build();

	@Test
	void deleteReturnsWrappedSuccessResponse() throws Exception {
		mockMvc.perform(delete("/skills/{skillCode}", "sample-skill"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.successful").value(true))
			.andExpect(jsonPath("$.code").value(200));

		verify(skillCatalogService).delete("sample-skill");
	}

}
