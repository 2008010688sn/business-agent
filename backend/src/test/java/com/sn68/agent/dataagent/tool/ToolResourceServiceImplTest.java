/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.tool.ToolPermissionResult;
import com.sn68.agent.dataagent.dto.tool.ToolTestReq;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolResourceServiceImplTest {

	private final ToolDirectoryService directoryService = mock(ToolDirectoryService.class);

	private final AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);

	private final AgentExecutionResourceVersionMapper versionMapper = mock(AgentExecutionResourceVersionMapper.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final ToolPermissionService permissionService = mock(ToolPermissionService.class);

	private final ToolTransportInvoker transportInvoker = mock(ToolTransportInvoker.class);

	private final ToolResourceServiceImpl service = new ToolResourceServiceImpl(directoryService, resourceMapper,
			versionMapper, authenticationContext, permissionService, transportInvoker, new ObjectMapper());

	@Test
	void rejectsWriteToolTestsBeforeTransportInvocation() {
		stubResource();
		when(versionMapper.findPublished(2L)).thenReturn(version("WRITE", "[]"));

		assertThrows(CheckedException.class,
				() -> service.test("demo.echo", new ToolTestReq(2L, Map.of())));
		verify(transportInvoker, never()).invoke(any(AgentExecutionResource.class), any());
	}

	@Test
	void invokesPublishedReadSnapshotAndMasksSensitiveFields() {
		stubResource();
		when(versionMapper.findPublished(2L)).thenReturn(version("READ", "[\"phone\"]"));
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(permissionService.canAccess(List.of("waybill:query"), null))
			.thenReturn(new ToolPermissionResult(true, null));
		when(transportInvoker.invoke(any(AgentExecutionResource.class), any()))
			.thenReturn(Map.of("phone", "13800000000", "nested", Map.of("phone", "13900000000")));

		Map<String, Object> result = service.test("demo.echo", new ToolTestReq(2L, Map.of("no", "WB1")));

		assertEquals("****", result.get("phone"));
		assertEquals("****", ((Map<?, ?>) result.get("nested")).get("phone"));
		verify(transportInvoker).invoke(any(AgentExecutionResource.class), any());
	}

	private void stubResource() {
		when(resourceMapper.findEnabledByResourceKey("demo.echo"))
			.thenReturn(AgentExecutionResource.builder().resourceKey("demo.echo").enabled(true).build());
	}

	private AgentExecutionResourceVersion version(String accessMode, String sensitiveFields) {
		return AgentExecutionResourceVersion.builder().id(2L).tenantId("tenant-1")
			.resourceKey("demo.echo").status("PUBLISHED").accessMode(accessMode)
			.permissionCode("waybill:query").sensitiveFields(sensitiveFields)
			.snapshot("{\"resourceKey\":\"demo.echo\",\"resourceType\":\"MCP_TOOL\","
					+ "\"serverCode\":\"zeus\",\"toolName\":\"query\"}")
			.build();
	}

}
