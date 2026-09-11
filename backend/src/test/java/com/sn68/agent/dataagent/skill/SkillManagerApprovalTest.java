/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillManagerApprovalTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void missingOrFalseIsDisabled() {
		assertFalse(SkillManagerApproval.enabled((Map<String, Object>) null));
		assertFalse(SkillManagerApproval.enabled(Map.of()));
		assertFalse(SkillManagerApproval.enabled(Map.of("requireManagerApproval", false)));
		assertFalse(SkillManagerApproval.enabled("{}", objectMapper));
		assertFalse(SkillManagerApproval.enabled((String) null, objectMapper));
	}

	@Test
	void trueEnables() {
		assertTrue(SkillManagerApproval.enabled(Map.of("requireManagerApproval", true)));
		assertTrue(SkillManagerApproval.enabled("{\"requireManagerApproval\":true}", objectMapper));
	}

}
