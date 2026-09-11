/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillVersionResourceLoaderTest {

	@Test
	void load_preservesOptionalNullValuesInAnImmutablePublishedSnapshot() {
		DataAgentSkillVersion version = DataAgentSkillVersion.builder()
			.id(12L)
			.skillId(7L)
			.datasourceConfig("""
					{"datasourceId":8,"readOnly":true,"maxRows":null,
					 "tables":[{"table":"orders","columns":["id"]}],
					 "maskingPolicy":{"mobile":null},"permissionPolicy":{"site":null}}
					""")
			.runtimeConfig("{\"maxAttempts\":null}")
			.build();

		SkillVersionResources resources = new SkillVersionResourceLoader(new ObjectMapper()).load(version);

		assertEquals(7L, resources.skillId());
		assertEquals(8L, resources.datasourceId());
		assertNull(resources.datasource().maxRows());
		assertNull(resources.datasource().maskingPolicy().get("mobile"));
		assertNull(resources.runtimeConfig().get("maxAttempts"));
		assertThrows(UnsupportedOperationException.class,
				() -> resources.runtimeConfig().put("other", true));
	}
}
