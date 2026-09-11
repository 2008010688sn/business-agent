/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 来源守卫 extConfig 附加可信主机测试：声明主机并入白名单；无声明与脏配置行为不变（失败关闭）。
 */
class CapabilitySourceGuardExtConfigTest {

	private CapabilitySourceGuard guard;

	@BeforeEach
	void setUp() {
		guard = new CapabilitySourceGuard(mock(AgentExecutionResourceMapper.class), new ObjectMapper());
	}

	@Test
	void extConfigTrustedHostsJoinWhitelist() {
		AgentExecutionResource resource = resource(
				"{\"trustedHosts\":[\"open.example.com\",\"https://cb.example.com/path\"]}");

		assertDoesNotThrow(() -> guard.requireDeclaredNetworkAddresses(
				request(Map.of("callbackUrl", "https://open.example.com/notify")), resource));
		assertDoesNotThrow(() -> guard.requireDeclaredNetworkAddresses(
				request(Map.of("callbackUrl", "https://cb.example.com/notify")), resource));
	}

	@Test
	void undeclaredHostIsStillRejected() {
		AgentExecutionResource resource = resource("{\"trustedHosts\":[\"open.example.com\"]}");

		assertThrows(CheckedException.class, () -> guard.requireDeclaredNetworkAddresses(
				request(Map.of("callbackUrl", "https://evil.example.com/x")), resource));
	}

	@Test
	void missingDeclarationKeepsExistingBehavior() {
		AgentExecutionResource resource = resource(null);
		resource.setEndpointUrl("https://api.example.com/v1");

		assertDoesNotThrow(() -> guard.requireDeclaredNetworkAddresses(
				request(Map.of("callbackUrl", "https://api.example.com/cb")), resource));
		assertThrows(CheckedException.class, () -> guard.requireDeclaredNetworkAddresses(
				request(Map.of("callbackUrl", "https://open.example.com/cb")), resource));
	}

	@Test
	void corruptExtConfigFailsClosedToBaseWhitelist() {
		AgentExecutionResource resource = resource("not-json");
		resource.setEndpointUrl("https://api.example.com/v1");

		assertThrows(CheckedException.class, () -> guard.requireDeclaredNetworkAddresses(
				request(Map.of("callbackUrl", "https://open.example.com/cb")), resource));
		assertDoesNotThrow(() -> guard.requireDeclaredNetworkAddresses(
				request(Map.of("callbackUrl", "https://api.example.com/cb")), resource));
	}

	private InvocationRequest request(Map<String, Object> arguments) {
		return InvocationRequest.builder()
			.capabilityKind(CapabilityKind.TOOL)
			.capabilityCode("crm:update")
			.arguments(arguments)
			.build();
	}

	private AgentExecutionResource resource(String extConfig) {
		return AgentExecutionResource.builder().resourceKey("crm:update").extConfig(extConfig).build();
	}

}
