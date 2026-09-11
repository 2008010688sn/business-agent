/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepInvocationInspector;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import com.sn68.agent.dataagent.dto.tool.ToolPermissionResult;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeBudgetService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeInvocationService;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageLimitService;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageReservation;
import com.sn68.agent.dataagent.tool.ToolPermissionService;
import com.sn68.agent.dataagent.tool.ToolTransportInvoker;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 能力网关 DRY_RUN 副作用禁令测试（方案第十四章离线评估约束）：写能力/外部副作用能力在
 * DRY_RUN 下失败关闭地拦截并记违规；只读能力照常执行；隔离类拒绝计入隔离违规；
 * 显式意图 + 作用域注册表通道在线程切换（无 ThreadLocal）场景下仍生效。
 */
class DefaultCapabilityGatewayDryRunTest {

	private static final String WRITE_CAPABILITY = "crm:update";

	private static final String READ_CAPABILITY = "crm:query";

	private static final String TENANT = "7";

	private AgentExecutionResourceVersionMapper resourceVersionMapper;

	private ToolTransportInvoker toolTransportInvoker;

	private AgentApprovalService approvalService;

	private ToolPermissionService toolPermissionService;

	private CapabilitySourceGuard sourceGuard;

	private DefaultCapabilityGateway gateway;

	private ExecutorService prewarmedExecutor;

	@BeforeEach
	void setUp() throws Exception {
		sourceGuard = mock(CapabilitySourceGuard.class);
		resourceVersionMapper = mock(AgentExecutionResourceVersionMapper.class);
		toolTransportInvoker = mock(ToolTransportInvoker.class);
		approvalService = mock(AgentApprovalService.class);
		toolPermissionService = mock(ToolPermissionService.class);
		AgentUsageLimitService usageLimitService = mock(AgentUsageLimitService.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		PepInvocationInspector pepInvocationInspector = mock(PepInvocationInspector.class);
		// PEP 影子组件 mock：透传输出义务语义，保持既有 envelope.data() 断言不变（PR-3c 接线兼容）。
		when(pepInvocationInspector.applyOutputObligations(any(), any(), any(), any()))
				.thenAnswer(invocation -> invocation.getArgument(2));
		gateway = new DefaultCapabilityGateway(sourceGuard, resourceVersionMapper, toolPermissionService,
				usageLimitService, toolTransportInvoker, authenticationContext, new ObjectMapper(),
				mock(RuntimeInvocationService.class), approvalService, mock(RuntimeBudgetService.class),
				pepInvocationInspector, mock(EmployeeReleaseSnapshotResolver.class),
				new PepAuthorizationProperties());
		when(authenticationContext.anonymous()).thenReturn(true);
		when(usageLimitService.preCheckAndReserve(any(), anyLong())).thenReturn(AgentUsageReservation.empty());
		when(sourceGuard.requireDeclaredResource(any(InvocationRequest.class))).thenAnswer(invocation -> {
			InvocationRequest request = invocation.getArgument(0);
			return AgentExecutionResource.builder().resourceKey(request.capabilityCode()).build();
		});
		// 预热线程池：让工作线程先于 DRY_RUN 作用域创建，验证注册表通道而非 ThreadLocal 继承。
		prewarmedExecutor = Executors.newSingleThreadExecutor();
		prewarmedExecutor.submit(() -> {
		}).get();
	}

	@AfterEach
	void tearDown() {
		prewarmedExecutor.shutdownNow();
	}

	@Test
	void dryRunBlocksWriteCatalogCapabilityAndRecordsViolation() {
		when(resourceVersionMapper.findLatestPublished(WRITE_CAPABILITY)).thenReturn(version(WRITE_CAPABILITY, "WRITE", false));
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();

		CheckedException ex = ExecutionIntentContext.supplyDryRun("dry-scope-1", collector,
				() -> assertThrows(CheckedException.class, () -> gateway.invoke(request(WRITE_CAPABILITY))));

		assertTrue(ex.getMessage().contains("DRY_RUN 副作用禁令拒绝"));
		assertEquals(1, collector.writeViolationCount());
		assertEquals(0, collector.isolationViolationCount());
		// 失败关闭：不执行传输层，也不产生审批副作用（不落 PENDING 审批记录）
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
		verify(approvalService, never()).createPending(any());
	}

	@Test
	void dryRunBlocksConfirmRequiredCatalogCapability() {
		when(resourceVersionMapper.findLatestPublished(WRITE_CAPABILITY)).thenReturn(version(WRITE_CAPABILITY, "READ", true));
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();

		ExecutionIntentContext.supplyDryRun("dry-scope-2", collector,
				() -> assertThrows(CheckedException.class, () -> gateway.invoke(request(WRITE_CAPABILITY))));

		assertEquals(1, collector.writeViolationCount());
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
	}

	@Test
	void dryRunBlocksCatalogCapabilityWithoutPublishedVersionFailClosed() {
		when(resourceVersionMapper.findLatestPublished(WRITE_CAPABILITY)).thenReturn(null);
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();

		CheckedException ex = ExecutionIntentContext.supplyDryRun("dry-scope-3", collector,
				() -> assertThrows(CheckedException.class, () -> gateway.invoke(request(WRITE_CAPABILITY))));

		assertTrue(ex.getMessage().contains("无法证明只读"));
		assertEquals(1, collector.writeViolationCount());
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
	}

	@Test
	void dryRunAllowsReadOnlyCatalogCapability() {
		when(resourceVersionMapper.findLatestPublished(READ_CAPABILITY)).thenReturn(version(READ_CAPABILITY, "READ", false));
		when(toolTransportInvoker.invoke(eq(READ_CAPABILITY), anyMap())).thenReturn(Map.of("ok", true));
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();

		ResultEnvelope envelope = ExecutionIntentContext.supplyDryRun("dry-scope-4", collector,
				() -> gateway.invoke(request(READ_CAPABILITY)));

		assertEquals(ResultEnvelope.STATUS_SUCCESS, envelope.status());
		assertEquals(0, collector.writeViolationCount());
		verify(toolTransportInvoker).invoke(eq(READ_CAPABILITY), anyMap());
	}

	@Test
	void dryRunAllowsReadOnlyInProcessDataQueryTool() {
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();

		ResultEnvelope envelope = ExecutionIntentContext.supplyDryRun("dry-scope-5", collector,
				() -> gateway.invoke(inProcessRequest("datasource_skill_search"), () -> "rows"));

		assertEquals("rows", envelope.data());
		assertEquals(0, collector.writeViolationCount());
	}

	@Test
	void dryRunBlocksWebFetchAsUnknownReadOnlyFamily() {
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();
		AtomicReference<Boolean> executed = new AtomicReference<>(false);

		CheckedException ex = ExecutionIntentContext.supplyDryRun("dry-scope-web-fetch", collector,
				() -> assertThrows(CheckedException.class,
						() -> gateway.invoke(inProcessRequest(AgentModelToolName.WEB_FETCH).toBuilder()
							.arguments(Map.of("url", "https://example.com/page"))
							.build(), () -> {
								executed.set(true);
								return "should-not-run";
							})));

		assertTrue(ex.getMessage().contains("DRY_RUN 副作用禁令拒绝"));
		assertEquals(1, collector.writeViolationCount());
		assertEquals(false, executed.get());
	}

	@Test
	void dryRunBlocksUnknownInProcessCapabilityFailClosed() {
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();
		AtomicReference<Boolean> executed = new AtomicReference<>(false);

		CheckedException ex = ExecutionIntentContext.supplyDryRun("dry-scope-6", collector,
				() -> assertThrows(CheckedException.class, () -> gateway.invoke(inProcessRequest("mcp_server__create_ticket"),
						() -> {
							executed.set(true);
							return "should-not-run";
						})));

		assertTrue(ex.getMessage().contains("DRY_RUN 副作用禁令拒绝"));
		assertEquals(1, collector.writeViolationCount());
		assertEquals(false, executed.get());
	}

	@Test
	void dryRunRecordsIsolationViolationWhenAuthorizationRejected() {
		AgentExecutionResourceVersion version = version(READ_CAPABILITY, "READ", false);
		version.setPermissionCode("crm:query:view");
		when(resourceVersionMapper.findLatestPublished(READ_CAPABILITY)).thenReturn(version);
		when(toolPermissionService.canAccess(anyList(), isNull()))
			.thenReturn(new ToolPermissionResult(false, "当前账号缺少该能力的功能权限"));
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();

		ExecutionIntentContext.supplyDryRun("dry-scope-7", collector,
				() -> assertThrows(CheckedException.class, () -> gateway.invoke(request(READ_CAPABILITY))));

		assertEquals(1, collector.isolationViolationCount());
		assertEquals(0, collector.writeViolationCount());
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
	}

	/**
	 * 线程切换场景：工作线程先于作用域创建（无 ThreadLocal 继承），仅凭 AgentRequest 显式透传的
	 * executionIntent + executionScopeKey 仍能拦截写能力并把违规回投到评估采集器。
	 */
	@Test
	void dryRunExplicitIntentBlocksWriteAcrossThreadsViaScopeRegistry() throws Exception {
		when(resourceVersionMapper.findLatestPublished(WRITE_CAPABILITY)).thenReturn(version(WRITE_CAPABILITY, "WRITE", false));
		ExecutionIntentContext.ViolationCollector collector = new ExecutionIntentContext.ViolationCollector();
		InvocationRequest explicitIntentRequest = request(WRITE_CAPABILITY).toBuilder()
			.executionIntent("DRY_RUN")
			.executionScopeKey("dry-scope-cross-thread")
			.build();

		ExecutionIntentContext.supplyDryRun("dry-scope-cross-thread", collector, () -> {
			try {
				Future<Throwable> failure = prewarmedExecutor.submit(() -> {
					try {
						gateway.invoke(explicitIntentRequest);
						return null;
					}
					catch (Throwable expected) {
						return expected;
					}
				});
				Throwable thrown = failure.get();
				assertNotNull(thrown, "DRY_RUN 下写能力必须被拦截");
				assertInstanceOf(CheckedException.class, thrown);
				assertTrue(thrown.getMessage().contains("DRY_RUN 副作用禁令拒绝"));
				return null;
			}
			catch (InterruptedException | java.util.concurrent.ExecutionException ex) {
				throw new IllegalStateException(ex);
			}
		});

		assertEquals(1, collector.writeViolationCount());
		verify(toolTransportInvoker, never()).invoke(anyString(), anyMap());
	}

	private InvocationRequest request(String capabilityCode) {
		return InvocationRequest.builder()
			.tenantId(String.valueOf(TENANT))
			.capabilityKind(CapabilityKind.TOOL)
			.capabilityCode(capabilityCode)
			.arguments(Map.of("name", "foo"))
			.source("TEST")
			.build();
	}

	private InvocationRequest inProcessRequest(String capabilityCode) {
		return InvocationRequest.builder()
			.tenantId(String.valueOf(TENANT))
			.capabilityKind(CapabilityKind.TOOL)
			.capabilityCode(capabilityCode)
			.arguments(Map.of())
			.source("AGENT_SCOPE")
			.build();
	}

	private AgentExecutionResourceVersion version(String capabilityCode, String accessMode, boolean confirmRequired) {
		return AgentExecutionResourceVersion.builder()
			.resourceKey(capabilityCode)
			.accessMode(accessMode)
			.confirmRequired(confirmRequired)
			.build();
	}

}
