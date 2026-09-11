/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.tool;

import com.sn68.agent.dataagent.agentscope.runtime.mcp.AgentScopeMcpToolName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModelToolNameTest {

	@Test
	void staticNamesSatisfyDeepSeekContract() {
		assertTrue(AgentModelToolName.isValid(AgentModelToolName.DATASOURCE_SKILL_SEARCH));
		assertTrue(AgentModelToolName.isValid(AgentModelToolName.SEMANTIC_MODEL_SEARCH));
		assertTrue(AgentModelToolName.isValid(AgentModelToolName.SQL_GUARD_CHECK));
		assertTrue(AgentModelToolName.isValid(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH));
		assertTrue(AgentModelToolName.isValid(AgentModelToolName.WEB_FETCH));
		assertTrue(AgentModelToolName.isWebEvidenceTool(AgentModelToolName.WEB_FETCH));
		assertFalse(AgentModelToolName.isWebEvidenceTool("web_search"));
		assertFalse(AgentModelToolName.isWebEvidenceTool("web_fetch_evil"));
		assertFalse(AgentModelToolName.isDataQueryTool(AgentModelToolName.WEB_FETCH));
		assertFalse(AgentModelToolName.isValid("datasource.skill.search"));
	}

	@Test
	void dynamicNamesAreStableBoundedAndDisambiguated() {
		String first = AgentModelToolName.skill("order.a", "query");
		String second = AgentModelToolName.skill("order/a", "query");
		String mcp = AgentScopeMcpToolName.alias("server.".repeat(20), "tool/".repeat(20));

		assertTrue(AgentModelToolName.isValid(first));
		assertTrue(AgentModelToolName.isValid(second));
		assertTrue(AgentModelToolName.isValid(mcp));
		assertEquals(first, AgentModelToolName.skill("order.a", "query"));
		assertNotEquals(first, second);
		assertTrue(mcp.length() <= 64);
	}

}
