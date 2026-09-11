/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.tool.webfetch;

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebFetchToolProviderTest {

	@Test
	void skillCatalogOmitsWebFetchWhenDisabled() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getWebEvidence().setFetchEnabled(false);
		WebFetchToolProvider provider = new WebFetchToolProvider(mock(WebFetchToolCallback.class), properties);

		assertTrue(provider.getSkillToolCallbacks(null).isEmpty());
	}

	@Test
	void skillCatalogIncludesWebFetchWhenEnabled() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getWebEvidence().setFetchEnabled(true);
		WebFetchToolProvider provider = new WebFetchToolProvider(mock(WebFetchToolCallback.class), properties);

		assertTrue(provider.getSkillToolCallbacks(null).containsKey(AgentModelToolName.WEB_FETCH));
		assertFalse(provider.getSkillToolCallbacks(null).isEmpty());
	}

}
