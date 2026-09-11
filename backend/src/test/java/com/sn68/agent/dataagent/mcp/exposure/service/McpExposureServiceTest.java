/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.mcp.exposure.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposureDTO;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposurePageQueryRequest;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposureRuntimeSyncResult;
import com.sn68.agent.dataagent.mcp.exposure.entity.AgentMcpExposure;
import com.sn68.agent.dataagent.mcp.exposure.repository.AgentMcpExposureMapper;
import com.sn68.agent.dataagent.mcp.exposure.service.McpExposureRuntimeSyncEvent.Reason;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpExposureServiceTest {

	private static final String EXPOSURE_CODE = "customer_query";

	private static final String TOOL_KEY = "customer.data.query";

	private static final String TOOL_NAME = "customer_data_query";

	private final AgentMcpExposureMapper exposureMapper = mock(AgentMcpExposureMapper.class);

	private final AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);

	private final McpExposureRuntimeRegistry runtimeRegistry = mock(McpExposureRuntimeRegistry.class);

	private final McpExposureRuntimeSyncCoordinator runtimeSyncCoordinator =
			mock(McpExposureRuntimeSyncCoordinator.class);

	private final McpExposureService service = new McpExposureService(exposureMapper, resourceMapper,
			new ObjectMapper(), runtimeRegistry, runtimeSyncCoordinator);

	@Test
	void listAndPageOnlyReadExposureMapper() {
		McpExposurePageQueryRequest request = new McpExposurePageQueryRequest();
		when(exposureMapper.findAllOrdered(null, null)).thenReturn(List.of());
		when(exposureMapper.selectExposurePage(org.mockito.ArgumentMatchers.<IPage<AgentMcpExposure>>any(), same(request)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		service.list(null, null);
		service.page(request);

		verify(exposureMapper).findAllOrdered(null, null);
		verify(exposureMapper).selectExposurePage(org.mockito.ArgumentMatchers.<IPage<AgentMcpExposure>>any(),
				same(request));
		verify(exposureMapper, never()).insert(any(AgentMcpExposure.class));
		verify(exposureMapper, never()).updateById(any(AgentMcpExposure.class));
		verify(exposureMapper, never()).deleteById(any());
		verify(exposureMapper, never()).restoreById(any());
		verifyNoInteractions(resourceMapper, runtimeRegistry, runtimeSyncCoordinator);
	}

	@Test
	void createRejectsActiveDuplicateExposureCode() {
		when(exposureMapper.findByExposureCodeIncludingDeleted(EXPOSURE_CODE)).thenReturn(exposure(false));

		CheckedException error = assertThrows(CheckedException.class, () -> service.create(request("disabled")));

		assertEquals("MCP 暴露编码已存在: " + EXPOSURE_CODE, error.getMessage());
		verify(exposureMapper, never()).insert(any(AgentMcpExposure.class));
	}

	@Test
	void createRestoresLogicallyDeletedExposureWithOriginalId() {
		AgentMcpExposure deleted = exposure(true);
		when(exposureMapper.findByExposureCodeIncludingDeleted(EXPOSURE_CODE)).thenReturn(deleted);
		when(exposureMapper.findByExposureCode(EXPOSURE_CODE)).thenReturn(deleted);

		McpExposureDTO result = service.create(request("disabled"));

		assertEquals(41L, result.id());
		verify(exposureMapper).restoreById(41L);
		verify(exposureMapper).updateById(deleted);
		verify(exposureMapper, never()).insert(any(AgentMcpExposure.class));
		verify(runtimeSyncCoordinator).requestAfterCommit(Reason.CREATE);
	}

	@Test
	void createTranslatesConcurrentDuplicateKeyToBusinessError() {
		when(exposureMapper.findByExposureCodeIncludingDeleted(EXPOSURE_CODE)).thenReturn(null);
		when(exposureMapper.insert(any(AgentMcpExposure.class)))
			.thenThrow(new DuplicateKeyException("agent_mcp_exposure_exposure_code_key"));

		CheckedException error = assertThrows(CheckedException.class, () -> service.create(request("disabled")));

		assertEquals("MCP 暴露编码已存在: " + EXPOSURE_CODE, error.getMessage());
		verify(runtimeSyncCoordinator, never()).requestAfterCommit(any());
	}

	@Test
	void enabledExposureRequiresEnabledExecutionResource() {
		when(exposureMapper.findByExposureCodeIncludingDeleted(EXPOSURE_CODE)).thenReturn(null);

		CheckedException error = assertThrows(CheckedException.class, () -> service.create(request("enabled")));

		assertEquals("启用 MCP 暴露时，内部 Tool 不存在或未启用: " + TOOL_KEY, error.getMessage());
		verify(exposureMapper, never()).insert(any(AgentMcpExposure.class));
	}

	@Test
	void enabledExposureRejectsDuplicateExternalToolName() {
		when(exposureMapper.findByExposureCodeIncludingDeleted(EXPOSURE_CODE)).thenReturn(null);
		when(resourceMapper.findEnabledByResourceKey(TOOL_KEY)).thenReturn(resource());
		when(exposureMapper.findEnabledByExposedToolName(TOOL_NAME))
			.thenReturn(AgentMcpExposure.builder().id(99L).exposedToolName(TOOL_NAME).build());

		CheckedException error = assertThrows(CheckedException.class, () -> service.create(request("enabled")));

		assertEquals("MCP Tool Name 已被其他启用配置占用: " + TOOL_NAME, error.getMessage());
	}

	@Test
	void enabledExposureRejectsStaticToolName() {
		when(exposureMapper.findByExposureCodeIncludingDeleted(EXPOSURE_CODE)).thenReturn(null);
		when(resourceMapper.findEnabledByResourceKey(TOOL_KEY)).thenReturn(resource());
		when(runtimeRegistry.isStaticToolName(TOOL_NAME)).thenReturn(true);

		CheckedException error = assertThrows(CheckedException.class, () -> service.create(request("enabled")));

		assertEquals("MCP Tool Name 与系统静态 Tool 冲突: " + TOOL_NAME, error.getMessage());
	}

	@Test
	void updateAndDeleteTriggerRuntimeReconcileAfterPersistence() {
		AgentMcpExposure existing = exposure(false);
		when(exposureMapper.selectById(41L)).thenReturn(existing, existing);
		when(exposureMapper.findByExposureCodeIncludingDeleted(EXPOSURE_CODE)).thenReturn(existing);

		service.update(41L, request("disabled"));
		service.delete(41L);

		verify(exposureMapper).updateById(existing);
		verify(exposureMapper).deleteById(41L);
		verify(runtimeSyncCoordinator).requestAfterCommit(Reason.UPDATE);
		verify(runtimeSyncCoordinator).requestAfterCommit(Reason.DELETE);
	}

	@Test
	void manualRuntimeSyncReturnsCoordinatorResult() {
		McpExposureRuntimeSyncResult expected =
				new McpExposureRuntimeSyncResult(true, 2L, "event-1", java.time.Instant.now());
		when(runtimeSyncCoordinator.synchronizeManually()).thenReturn(expected);

		McpExposureRuntimeSyncResult result = service.syncRuntime();

		assertEquals(expected, result);
		verify(runtimeSyncCoordinator).synchronizeManually();
	}

	private McpExposureDTO request(String status) {
		return new McpExposureDTO(null, EXPOSURE_CODE, "客户查询", TOOL_KEY, TOOL_NAME, "TOOL", "LOW", true,
				status, 0, Map.of());
	}

	private AgentMcpExposure exposure(boolean deleted) {
		return AgentMcpExposure.builder()
			.id(41L)
			.exposureCode(EXPOSURE_CODE)
			.exposureName("客户查询")
			.toolKey(TOOL_KEY)
			.exposedToolName(TOOL_NAME)
			.exposureType("TOOL")
			.riskLevel("LOW")
			.requireUserContext(true)
			.status("disabled")
			.displayOrder(0)
			.deleted(deleted)
			.build();
	}

	private AgentExecutionResource resource() {
		return AgentExecutionResource.builder()
			.id(7L)
			.resourceKey(TOOL_KEY)
			.resourceName("客户数据查询")
			.enabled(true)
			.status("enabled")
			.build();
	}

}
