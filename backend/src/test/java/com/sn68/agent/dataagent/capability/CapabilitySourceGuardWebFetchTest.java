/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 定 B：web_fetch 精确豁免 declared-hosts；其它进程内 url 参数仍拒绝。禁止 web_* 通配。
 */
class CapabilitySourceGuardWebFetchTest {

	private CapabilitySourceGuard guard;

	@BeforeEach
	void setUp() {
		guard = new CapabilitySourceGuard(mock(AgentExecutionResourceMapper.class), new ObjectMapper());
	}

	@Test
	void inProcessUrlParameterIsStillRejected() {
		assertThrows(CheckedException.class,
				() -> guard.requireDeclaredNetworkAddresses(request("datasource_skill_search",
						Map.of("url", "https://example.com/page")), null));
	}

	@Test
	void webFetchIsExemptFromDeclaredHosts() {
		assertDoesNotThrow(() -> guard.requireDeclaredNetworkAddresses(
				request(AgentModelToolName.WEB_FETCH, Map.of("url", "https://example.com/page")), null));
	}

	@Test
	void webPrefixedNamesAreNotWildcardExempt() {
		assertThrows(CheckedException.class, () -> guard.requireDeclaredNetworkAddresses(
				request("web_search", Map.of("url", "https://example.com/page")), null));
		assertThrows(CheckedException.class, () -> guard.requireDeclaredNetworkAddresses(
				request("web_fetch_evil", Map.of("url", "https://example.com/page")), null));
	}

	private InvocationRequest request(String capabilityCode, Map<String, Object> arguments) {
		return InvocationRequest.builder()
			.capabilityKind(CapabilityKind.TOOL)
			.capabilityCode(capabilityCode)
			.arguments(arguments)
			.build();
	}

}
