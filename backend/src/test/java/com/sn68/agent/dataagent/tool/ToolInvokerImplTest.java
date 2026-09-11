/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import com.sn68.agent.dataagent.dto.tool.ToolPermissionResult;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentFlowInstanceMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.service.skill.SkillBindingService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolInvokerImplTest {

	private final AgentExecutionResourceVersionMapper versionMapper = mock(AgentExecutionResourceVersionMapper.class);

	private final AgentExecutionResourceMapper resourceMapper = mock(AgentExecutionResourceMapper.class);

	private final DataAgentSkillToolRefMapper refMapper = mock(DataAgentSkillToolRefMapper.class);

	private final DataAgentFlowInstanceMapper flowInstanceMapper = mock(DataAgentFlowInstanceMapper.class);

	private final DataAgentSkillVersionMapper skillVersionMapper = mock(DataAgentSkillVersionMapper.class);

	private final DataAgentSkillMapper skillMapper = mock(DataAgentSkillMapper.class);

	private final ToolTransportInvoker delegate = mock(ToolTransportInvoker.class);

	private final SkillBindingService bindingService = mock(SkillBindingService.class);

	private final ToolPermissionService permissionService = mock(ToolPermissionService.class);

	private final ToolInvokerImpl invoker = new ToolInvokerImpl(versionMapper, resourceMapper, refMapper,
			flowInstanceMapper, skillVersionMapper, skillMapper, bindingService, permissionService, delegate,
			new ObjectMapper());

	@Test
	void refusesWriteBeforeConfirmation() {
		AgentExecutionResourceVersion version = version("WRITE", "FLOW_ONLY");
		when(versionMapper.findPublished(20L)).thenReturn(version);
		when(refMapper.findBySkillVersionId(10L)).thenReturn(List.of(ref()));

		allowFlowBinding("EXECUTING", "execute-demand", 1);
		assertThrows(CheckedException.class, () -> invoker.invoke(
				new ToolInvocationContext(1L, "tenant-1", 10L, 20L, "WRITE", false, "key", Map.of(),
						flowReference("execute-demand", 1))));
	}

	@Test
	void injectsServerIdempotencyKeyForConfirmedWrite() {
		AgentExecutionResourceVersion version = version("WRITE", "FLOW_ONLY");
		when(versionMapper.findPublished(20L)).thenReturn(version);
		when(refMapper.findBySkillVersionId(10L)).thenReturn(List.of(ref()));
		when(delegate.invoke(org.mockito.ArgumentMatchers.any(AgentExecutionResource.class),
				org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("demandNo", "D1"));
		allowFlowBinding("EXECUTING", "execute-demand", 1);

		Map<String, Object> result = invoker.invoke(
				new ToolInvocationContext(1L, "tenant-1", 10L, 20L, "WRITE", true,
						"flow:t:1:execute", Map.of(), flowReference("execute-demand", 1)));

		assertEquals("D1", result.get("demandNo"));
		verify(delegate).invoke(org.mockito.ArgumentMatchers.<AgentExecutionResource>argThat(
				resource -> "demo.echo.execute".equals(resource.getResourceKey())),
				org.mockito.ArgumentMatchers.argThat(args -> "flow:t:1:execute".equals(args.get("idempotencyKey"))));
	}

	@Test
	void rejectsSnapshotWhoseResourceKeyWasChanged() {
		AgentExecutionResourceVersion version = version("READ", "MODEL");
		version.setSnapshot("{\"resourceKey\":\"other.tool\",\"resourceType\":\"MCP_TOOL\"}");
		when(versionMapper.findPublished(20L)).thenReturn(version);
		when(refMapper.findBySkillVersionId(10L)).thenReturn(List.of(ref()));

		allowBinding();
		assertThrows(CheckedException.class, () -> invoker.invoke(
				new ToolInvocationContext(1L, "tenant-1", 10L, 20L, "READ", false, null, Map.of())));
	}

	@Test
	void allowsPublishedVersionPinnedByActiveFlowWhenAgentMovedToNewVersion() {
		AgentExecutionResourceVersion version = version("READ", "FLOW_ONLY");
		when(versionMapper.findPublished(20L)).thenReturn(version);
		when(refMapper.findBySkillVersionId(10L)).thenReturn(List.of(ref()));
		when(delegate.invoke(org.mockito.ArgumentMatchers.any(AgentExecutionResource.class),
				org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("items", List.of()));
		allowFlowBinding("RUNNING", "resolve-customer", 3);

		Map<String, Object> result = invoker.invoke(new ToolInvocationContext(1L, "tenant-1", 10L, 20L,
				"READ", false, null, Map.of(), flowReference("resolve-customer", 3)));

		assertEquals(List.of(), result.get("items"));
	}

	@Test
	void rejectsOldVersionWithoutServerFlowReference() {
		when(bindingService.listEnabled(1L, "tenant-1")).thenReturn(List.of(DataAgentSkillBinding.builder()
				.agentId(1L).tenantId("tenant-1").skillId(5L).pinnedSkillVersionId(11L).enabled(true)
				.build()));

		assertThrows(ToolInvocationException.class, () -> invoker.invoke(
				new ToolInvocationContext(1L, "tenant-1", 10L, 20L, "READ", false, null, Map.of())));
	}

	@Test
	void rejectsStaleFlowLockVersion() {
		allowFlowBinding("RUNNING", "resolve-customer", 4);

		ToolInvocationException error = assertThrows(ToolInvocationException.class, () -> invoker.invoke(
				new ToolInvocationContext(1L, "tenant-1", 10L, 20L, "READ", false, null, Map.of(),
						flowReference("resolve-customer", 3))));

		assertEquals(ToolInvocationException.Code.FLOW_STATE_STALE, error.getCode());
	}

	/**
	 * 方案第十四章 DRY_RUN 副作用禁令：即便写工具已确认（confirmed=true、FLOW EXECUTING），
	 * 离线评估干跑下仍必须在进传输层前失败关闭地拦截并记违规——覆盖 FLOW 写提交路径。
	 */
	@Test
	void dryRunRejectsConfirmedWriteToolBeforeTransportAndRecordsViolation() {
		AgentExecutionResourceVersion version = version("WRITE", "FLOW_ONLY");
		when(versionMapper.findPublished(20L)).thenReturn(version);
		when(refMapper.findBySkillVersionId(10L)).thenReturn(List.of(ref()));
		allowFlowBinding("EXECUTING", "execute-demand", 1);
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();

		CheckedException ex = ExecutionIntentContext.supplyDryRun("dry-scope-tool", collector,
				() -> assertThrows(CheckedException.class, () -> invoker.invoke(
						new ToolInvocationContext(1L, "tenant-1", 10L, 20L, "WRITE", true, "flow:t:1:execute",
								Map.of(), flowReference("execute-demand", 1)))));

		assertTrue(ex.getMessage().contains("DRY_RUN 副作用禁令拒绝"));
		assertEquals(1, collector.writeViolationCount());
		verify(delegate, never()).invoke(org.mockito.ArgumentMatchers.any(AgentExecutionResource.class),
				org.mockito.ArgumentMatchers.anyMap());
	}

	/** DRY_RUN 只限制写工具：只读工具在干跑下照常执行，保证离线评估可用。 */
	@Test
	void dryRunStillAllowsReadTool() {
		AgentExecutionResourceVersion version = version("READ", "FLOW_ONLY");
		when(versionMapper.findPublished(20L)).thenReturn(version);
		when(refMapper.findBySkillVersionId(10L)).thenReturn(List.of(ref()));
		when(delegate.invoke(org.mockito.ArgumentMatchers.any(AgentExecutionResource.class),
				org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of("items", List.of()));
		allowFlowBinding("RUNNING", "resolve-customer", 3);
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();

		Map<String, Object> result = ExecutionIntentContext.supplyDryRun("dry-scope-tool-read", collector,
				() -> invoker.invoke(new ToolInvocationContext(1L, "tenant-1", 10L, 20L, "READ", false, null,
						Map.of(), flowReference("resolve-customer", 3))));

		assertEquals(List.of(), result.get("items"));
		assertEquals(0, collector.writeViolationCount());
	}

	private AgentExecutionResourceVersion version(String accessMode, String exposureMode) {
		return AgentExecutionResourceVersion.builder().id(20L).resourceKey("demo.echo.execute")
			.resourceId(30L)
			.accessMode(accessMode).exposureMode(exposureMode).idempotencyRequired(true)
			.snapshot("{\"resourceKey\":\"demo.echo.execute\",\"resourceType\":\"MCP_TOOL\","
					+ "\"serverCode\":\"zeus\",\"toolName\":\"echoExecute\"}")
			.build();
	}

	private DataAgentSkillToolRef ref() {
		return DataAgentSkillToolRef.builder().skillVersionId(10L).resourceVersionId(20L).build();
	}

	private void allowBinding() {
		when(bindingService.listEnabled(1L, "tenant-1")).thenReturn(List.of(DataAgentSkillBinding.builder()
			.agentId(1L).tenantId("tenant-1").skillId(5L).pinnedSkillVersionId(10L).enabled(true).build()));
		allowResource();
		when(permissionService.canAccess(org.mockito.ArgumentMatchers.anyList(),
				org.mockito.ArgumentMatchers.isNull())).thenReturn(new ToolPermissionResult(true, null));
	}

	private void allowFlowBinding(String status, String nodeId, int lockVersion) {
		when(flowInstanceMapper.selectById(100L)).thenReturn(DataAgentFlowInstance.builder().id(100L)
				.tenantId("tenant-1").agentId(1L).skillId(5L).skillVersionId(10L).skillCode("demand-create")
				.threadId("thread-1").userId("user-1").status(status).currentNodeId(nodeId)
				.lockVersion(lockVersion).lastRuntimeRequestId("runtime-1").idempotencyKey("flow:t:1:execute")
				.expiresAt(Instant.now().plusSeconds(60)).deleted(false).build());
		when(skillVersionMapper.selectById(10L)).thenReturn(DataAgentSkillVersion.builder().id(10L).skillId(5L)
				.status("PUBLISHED").deleted(false).flowDefinition(flowDefinition(nodeId)).build());
		when(skillMapper.selectById(5L)).thenReturn(DataAgentSkill.builder().id(5L).skillCode("demand-create")
				.status("PUBLISHED").deleted(false).build());
		when(bindingService.listEnabled(1L, "tenant-1")).thenReturn(List.of(DataAgentSkillBinding.builder()
				.agentId(1L).tenantId("tenant-1").skillId(5L).pinnedSkillVersionId(11L).enabled(true)
				.build()));
		allowResource();
		when(permissionService.canAccess(org.mockito.ArgumentMatchers.anyList(),
				org.mockito.ArgumentMatchers.isNull())).thenReturn(new ToolPermissionResult(true, null));
	}

	private void allowResource() {
		when(resourceMapper.selectById(30L)).thenReturn(AgentExecutionResource.builder().id(30L)
				.resourceKey("demo.echo.execute").enabled(true).status("enabled").deleted(false).build());
	}

	private FlowInvocationReference flowReference(String nodeId, int lockVersion) {
		return new FlowInvocationReference(100L, lockVersion, nodeId, nodeId, "thread-1", "user-1",
				"runtime-1");
	}

	private String flowDefinition(String nodeId) {
		String nodeType = "execute-demand".equals(nodeId) ? "execute" : "resolve";
		return "{\"nodes\":[{\"id\":\"" + nodeId + "\",\"type\":\"" + nodeType
				+ "\",\"config\":{\"resourceVersionId\":20}}]}";
	}

}
