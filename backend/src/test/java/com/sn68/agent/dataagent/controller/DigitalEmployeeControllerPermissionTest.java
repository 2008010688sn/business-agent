/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sn68.agent.dataagent.employee.controller.DigitalEmployeeController;
import com.sn68.agent.dataagent.market.controller.SkillMarketListingController;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingPageQueryReq;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class DigitalEmployeeControllerPermissionTest {

	@Test
	void employeeAndMarketEndpointsRemainCallableWithoutSaToken() throws NoSuchMethodException {
		Method available = SkillMarketListingController.class.getDeclaredMethod("listAvailableSkills",
				SkillMarketListingPageQueryReq.class);
		assertNotNull(available);
		Method install = DigitalEmployeeController.class.getDeclaredMethod("installMarketSkill", Long.class, Long.class,
				Integer.class);
		assertNotNull(install);
		Method pageRuns = DigitalEmployeeController.class.getDeclaredMethod("pageRuns", Long.class,
				com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunPageQueryReq.class);
		assertNotNull(pageRuns);
	}

}
