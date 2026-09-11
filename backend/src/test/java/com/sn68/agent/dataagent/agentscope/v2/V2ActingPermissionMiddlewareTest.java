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
package com.sn68.agent.dataagent.agentscope.v2;

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionResult;
import com.sn68.agent.dataagent.authorization.pep.RuntimePolicyEvaluator;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.permission.PermissionBehavior;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2ActingPermissionMiddlewareTest {

	private final RuntimePolicyEvaluator pep = mock(RuntimePolicyEvaluator.class);

	private final AgentExecutionResourceVersionMapper versions = mock(AgentExecutionResourceVersionMapper.class);

	private final PepAuthorizationProperties pepProperties = new PepAuthorizationProperties();

	private final V2ActingPermissionMiddleware middleware = new V2ActingPermissionMiddleware(pep, versions,
			pepProperties);

	private final Agent agent = mock(Agent.class);

	@Test
	void sqlWriteDeniedDoesNotCrashAgent() {
		when(pep.evaluate(any())).thenThrow(new IllegalStateException("pep exploded"));
		AtomicBoolean nextCalled = new AtomicBoolean();
		ActingInput acting = acting(AgentModelToolName.DATASOURCE_SKILL_SEARCH,
				Map.of("action", "SEARCH", "sql", "INSERT INTO orders(id) VALUES (1)"));

		List<AgentEvent> events = middleware
			.onActing(agent, context("tenant-9", "user-3"), acting, input -> {
				nextCalled.set(true);
				return Flux.error(new IllegalStateException("tool must not run"));
			})
			.collectList()
			.block();

		assertFalse(nextCalled.get());
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultEndEvent end
				&& end.getState() == ToolResultState.DENIED));
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultTextDeltaEvent delta
				&& delta.getDelta().contains("SQL 守卫拒绝写语句")));
		verify(pep, never()).evaluate(any());
	}

	@Test
	void sqlReadAllowedContinuesToNext() {
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		AtomicReference<ActingInput> forwarded = new AtomicReference<>();
		AgentEvent passed = mock(AgentEvent.class);
		ActingInput acting = acting(AgentModelToolName.DATASOURCE_SKILL_SEARCH,
				Map.of("action", "SEARCH", "sql", "SELECT id FROM orders"));

		AgentEvent got = middleware
			.onActing(agent, context("tenant-9", "user-3"), acting, input -> {
				forwarded.set(input);
				return Flux.just(passed);
			})
			.blockFirst();

		assertEquals(passed, got);
		assertEquals(1, forwarded.get().toolCalls().size());
		assertEquals(AgentModelToolName.DATASOURCE_SKILL_SEARCH, forwarded.get().toolCalls().get(0).getName());
	}

	@Test
	void stackedStatementsAndUnparseableSqlAreDenied() {
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.SHADOW));
		RuntimeContext ctx = context("tenant-9", "user-3");
		V2RuntimeSnapshot snapshot = snapshot("tenant-9", "user-3");

		assertEquals(PermissionBehavior.DENY, middleware.checkPermissions(ctx, snapshot,
				tool(AgentModelToolName.DATASOURCE_SKILL_SEARCH, Map.of("sql", "SELECT 1; DELETE FROM orders")))
			.behavior());
		assertEquals(PermissionBehavior.DENY, middleware.checkPermissions(ctx, snapshot,
				tool(AgentModelToolName.DATASOURCE_SKILL_SEARCH, Map.of("sql", "NOT SQL"))).behavior());
	}

	@Test
	void pepEnforceDenyDoesNotCrashAgent() {
		when(pep.evaluate(any())).thenReturn(decision(false, PepAuthorizationMode.ENFORCE));
		AtomicBoolean nextCalled = new AtomicBoolean();
		ActingInput acting = acting(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH, Map.of("query", "q"));

		List<AgentEvent> events = middleware
			.onActing(agent, context("tenant-9", "user-3"), acting, input -> {
				nextCalled.set(true);
				return Flux.error(new IllegalStateException("tool must not run"));
			})
			.collectList()
			.block();

		assertFalse(nextCalled.get());
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultEndEvent end
				&& end.getState() == ToolResultState.DENIED));
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultTextDeltaEvent delta
				&& delta.getDelta().contains("PEP 拒绝")));
	}

	@Test
	void pepThrowOnReadToolIsDenyObservation() {
		when(pep.evaluate(any())).thenThrow(new IllegalStateException("pep exploded"));
		AtomicBoolean nextCalled = new AtomicBoolean();
		ActingInput acting = acting(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH, Map.of("query", "q"));

		List<AgentEvent> events = middleware.onActing(agent, context("tenant-9", "user-3"), acting, input -> {
			nextCalled.set(true);
			return Flux.error(new IllegalStateException("tool must not run"));
		}).collectList().block();

		assertFalse(nextCalled.get());
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultTextDeltaEvent delta
				&& delta.getDelta().contains("PEP 评估失败")));
	}

	@Test
	void pepUsesSnapshotTenantOwnerAndRunNeverHeaders() {
		when(pep.evaluate(any())).thenAnswer(invocation -> {
			PepDecisionContext pepContext = invocation.getArgument(0);
			assertEquals("tenant-9", pepContext.getTenantId());
			assertEquals("user-3", pepContext.getSubjectId());
			assertEquals(AuthorizationOwnerType.DATA_AGENT, pepContext.getOwnerType());
			assertEquals(1L, pepContext.getOwnerId());
			assertEquals(99L, pepContext.getRunId());
			assertEquals("run-1", pepContext.getStepKey());
			return decision(true, PepAuthorizationMode.ENFORCE);
		});
		V2RuntimeSnapshot snapshot = snapshot("tenant-9", "user-3");
		RuntimeContext ctx = RuntimeContext.builder()
			.sessionId("100")
			.userId("user-3")
			.put("X-Tenant-Id", "from-header")
			.put("tenantId", "from-header")
			.put(V2RuntimeSnapshot.class, snapshot)
			.build();
		AgentEvent passed = mock(AgentEvent.class);

		AgentEvent got = middleware
			.onActing(agent, ctx, acting(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH, Map.of("query", "q")),
					input -> Flux.just(passed))
			.blockFirst();

		assertEquals(passed, got);
	}

	@Test
	void enforceMissingOwnerIsDenyWithoutEvaluate() {
		pepProperties.setEnforceTenantIds(List.of("tenant-9"));
		V2RuntimeSnapshot snapshot = new V2RuntimeSnapshot("tenant-9", "T9", "user-3", null, null, null, List.of(),
				"100", "run-1", null, "DATA_AGENT", null, 99L);
		RuntimeContext ctx = RuntimeContext.builder().put(V2RuntimeSnapshot.class, snapshot).build();
		AtomicBoolean nextCalled = new AtomicBoolean();

		List<AgentEvent> events = middleware
			.onActing(agent, ctx, acting(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH, Map.of("query", "q")),
					input -> {
						nextCalled.set(true);
						return Flux.error(new IllegalStateException("tool must not run"));
					})
			.collectList()
			.block();

		assertFalse(nextCalled.get());
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultTextDeltaEvent delta
				&& delta.getDelta().contains("空主体拒绝")));
		verify(pep, never()).evaluate(any());
	}

	@Test
	void shadowPdpDenyStillAllowsReadTool() {
		when(pep.evaluate(any())).thenReturn(decision(false, PepAuthorizationMode.SHADOW));
		AgentEvent passed = mock(AgentEvent.class);

		AgentEvent got = middleware
			.onActing(agent, context("tenant-9", "user-3"),
					acting(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH, Map.of("query", "q")),
					input -> Flux.just(passed))
			.blockFirst();

		assertEquals(passed, got);
	}

	@Test
	void fileOnlyDeniesExecuteMcp() {
		RuntimeContext ctx = context("tenant-9", "user-3");
		ctx.put(V2ActingPermissionMiddleware.ANALYSIS_INTENT_KEY, V2ToolkitFilter.INTENT_FILE_ONLY);
		AtomicBoolean nextCalled = new AtomicBoolean();
		ActingInput acting = acting("crm__create_order", Map.of("orderId", "1"));

		List<AgentEvent> events = middleware.onActing(agent, ctx, acting, input -> {
			nextCalled.set(true);
			return Flux.error(new IllegalStateException("execute MCP must not run"));
		}).collectList().block();

		assertFalse(nextCalled.get());
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultTextDeltaEvent delta
				&& delta.getDelta().contains("分析通道禁止执行 MCP")));
	}

	@Test
	void fileOnlyDeniesJdbcEvenWhenConfirmed() {
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		RuntimeContext ctx = context("tenant-9", "user-3");
		ctx.put(V2ActingPermissionMiddleware.ANALYSIS_INTENT_KEY, V2ToolkitFilter.INTENT_FILE_ONLY);
		ToolUseBlock jdbc = ToolUseBlock.builder()
			.id("call-jdbc")
			.name(AgentModelToolName.DATASOURCE_SKILL_SEARCH)
			.input(Map.of("sql", "SELECT id FROM orders"))
			.state(ToolCallState.ALLOWED)
			.build();
		ctx.put(ConfirmResult.class, new ConfirmResult(true, jdbc));
		AtomicBoolean nextCalled = new AtomicBoolean();

		List<AgentEvent> events = middleware.onActing(agent, ctx, new ActingInput(List.of(jdbc)), input -> {
			nextCalled.set(true);
			return Flux.error(new IllegalStateException("FILE_ONLY JDBC must not run"));
		}).collectList().block();

		assertFalse(nextCalled.get());
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultTextDeltaEvent delta
				&& delta.getDelta().contains("FILE_ONLY")));
	}

	@Test
	void fileJoinSelectIsForwarded() {
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		RuntimeContext ctx = context("tenant-9", "user-3");
		ctx.put(V2ActingPermissionMiddleware.ANALYSIS_INTENT_KEY, V2ToolkitFilter.INTENT_FILE_JOIN);
		AgentEvent passed = mock(AgentEvent.class);

		AgentEvent got = middleware
			.onActing(agent, ctx, acting(AgentModelToolName.DATASOURCE_SKILL_SEARCH,
					Map.of("sql", "SELECT id FROM orders")), input -> Flux.just(passed))
			.blockFirst();

		assertEquals(passed, got);
	}

	@Test
	void writeToolAskEmitsRequireConfirmAndDoesNotExecute() {
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		AgentExecutionResourceVersion version = new AgentExecutionResourceVersion();
		version.setAccessMode("WRITE");
		when(versions.findLatestPublished("crm_update")).thenReturn(version);
		AtomicBoolean nextCalled = new AtomicBoolean();
		ToolUseBlock write = ToolUseBlock.builder().id("call-write").name("crm_update").input(Map.of("id", 1)).build();

		List<AgentEvent> events = middleware
			.onActing(agent, context("tenant-9", "user-3"), new ActingInput(List.of(write)), input -> {
				nextCalled.set(true);
				return Flux.error(new IllegalStateException("write must wait for confirm"));
			})
			.collectList()
			.block();

		assertFalse(nextCalled.get());
		assertTrue(events.stream().anyMatch(RequireUserConfirmEvent.class::isInstance));
		RequireUserConfirmEvent confirm = events.stream()
			.filter(RequireUserConfirmEvent.class::isInstance)
			.map(RequireUserConfirmEvent.class::cast)
			.findFirst()
			.orElseThrow();
		assertEquals("call-write", confirm.getToolCalls().get(0).getId());
		assertFalse(events.stream().anyMatch(event -> event instanceof ToolResultEndEvent end
				&& end.getState() == ToolResultState.DENIED));
		assertEquals(PermissionBehavior.ASK,
				middleware.checkPermissions(context("tenant-9", "user-3"), snapshot("tenant-9", "user-3"), write)
					.behavior());
		assertTrue(snapshot("tenant-9", "user-3").hitlEnabled());
	}

	@Test
	void writeToolDeniedWhenHitlDisabledDoesNotAskOrExecute() {
		V2ConfirmCredentialStore confirmStore = mock(V2ConfirmCredentialStore.class);
		V2ActingPermissionMiddleware gated = new V2ActingPermissionMiddleware(pep, versions, pepProperties,
				confirmStore);
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		AgentExecutionResourceVersion version = new AgentExecutionResourceVersion();
		version.setAccessMode("WRITE");
		when(versions.findLatestPublished("crm_update")).thenReturn(version);
		AtomicBoolean nextCalled = new AtomicBoolean();
		ToolUseBlock write = ToolUseBlock.builder().id("call-write").name("crm_update").input(Map.of("id", 1)).build();
		V2RuntimeSnapshot snapshot = snapshot("tenant-9", "user-3").withHitlEnabled(false);
		RuntimeContext ctx = RuntimeContext.builder()
			.sessionId("100")
			.userId("user-3")
			.put(V2RuntimeSnapshot.class, snapshot)
			.build();

		List<AgentEvent> events = gated.onActing(agent, ctx, new ActingInput(List.of(write)), input -> {
			nextCalled.set(true);
			return Flux.error(new IllegalStateException("write must not run without HITL"));
		}).collectList().block();

		assertFalse(nextCalled.get());
		assertFalse(events.stream().anyMatch(RequireUserConfirmEvent.class::isInstance));
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultEndEvent end
				&& end.getState() == ToolResultState.DENIED));
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultTextDeltaEvent delta
				&& delta.getDelta().contains(V2ActingPermissionMiddleware.HITL_DISABLED_OBSERVATION)));
		assertEquals(PermissionBehavior.DENY, gated.checkPermissions(ctx, snapshot, write).behavior());
		verify(confirmStore, never()).savePending(any(), any(), any());
	}

	@Test
	void confirmedWriteStillAllowedWhenHitlDisabled() {
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		ToolUseBlock write = ToolUseBlock.builder()
			.id("call-write")
			.name("crm_update")
			.input(Map.of("id", 1))
			.state(ToolCallState.ALLOWED)
			.build();
		RuntimeContext ctx = RuntimeContext.builder()
			.sessionId("100")
			.userId("user-3")
			.put(V2RuntimeSnapshot.class, snapshot("tenant-9", "user-3").withHitlEnabled(false))
			.build();
		AgentEvent passed = mock(AgentEvent.class);

		AgentEvent got = middleware.onActing(agent, ctx, new ActingInput(List.of(write)), input -> Flux.just(passed))
			.blockFirst();

		assertEquals(passed, got);
	}

	@Test
	void confirmResultAllowsPreviouslyAskedWrite() {
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		ToolUseBlock write = ToolUseBlock.builder()
			.id("call-write")
			.name("crm_update")
			.input(Map.of("id", 1))
			.state(ToolCallState.ALLOWED)
			.build();
		AgentEvent passed = mock(AgentEvent.class);

		AgentEvent got = middleware
			.onActing(agent, context("tenant-9", "user-3"), new ActingInput(List.of(write)),
					input -> Flux.just(passed))
			.blockFirst();

		assertEquals(passed, got);
	}

	@Test
	void confirmResultOnContextAllowsWriteWhenFingerprintMatches() {
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		ToolUseBlock write = ToolUseBlock.builder().id("call-write").name("crm_update").input(Map.of("id", 1)).build();
		RuntimeContext ctx = context("tenant-9", "user-3");
		ctx.put(ConfirmResult.class, new ConfirmResult(true, write));
		AgentEvent passed = mock(AgentEvent.class);

		AgentEvent got = middleware.onActing(agent, ctx, new ActingInput(List.of(write)), input -> Flux.just(passed))
			.blockFirst();

		assertEquals(passed, got);
	}

	@Test
	void allowedStateStillDeniesSqlWrite() {
		ToolUseBlock write = ToolUseBlock.builder()
			.id("call-sql")
			.name(AgentModelToolName.DATASOURCE_SKILL_SEARCH)
			.input(Map.of("sql", "INSERT INTO orders(id) VALUES (1)"))
			.state(ToolCallState.ALLOWED)
			.build();
		AtomicBoolean nextCalled = new AtomicBoolean();

		List<AgentEvent> events = middleware
			.onActing(agent, context("tenant-9", "user-3"), new ActingInput(List.of(write)), input -> {
				nextCalled.set(true);
				return Flux.error(new IllegalStateException("confirmed INSERT must still be denied"));
			})
			.collectList()
			.block();

		assertFalse(nextCalled.get());
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultTextDeltaEvent delta
				&& delta.getDelta().contains("SQL 守卫拒绝写语句")));
		verify(pep, never()).evaluate(any());
	}

	@Test
	void confirmWithMutatedSqlIsDenied() {
		ToolUseBlock approved = ToolUseBlock.builder()
			.id("call-sql")
			.name(AgentModelToolName.DATASOURCE_SKILL_SEARCH)
			.input(Map.of("sql", "SELECT id FROM orders"))
			.build();
		ToolUseBlock mutated = ToolUseBlock.builder()
			.id("call-sql")
			.name(AgentModelToolName.DATASOURCE_SKILL_SEARCH)
			.input(Map.of("sql", "DELETE FROM orders"))
			.build();
		RuntimeContext ctx = context("tenant-9", "user-3");
		ctx.put(ConfirmResult.class, new ConfirmResult(true, approved));
		AtomicBoolean nextCalled = new AtomicBoolean();

		List<AgentEvent> events = middleware.onActing(agent, ctx, new ActingInput(List.of(mutated)), input -> {
			nextCalled.set(true);
			return Flux.error(new IllegalStateException("mutated SQL must not run"));
		}).collectList().block();

		assertFalse(nextCalled.get());
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultTextDeltaEvent delta
				&& delta.getDelta().contains("SQL 守卫拒绝写语句")));
	}

	@Test
	void confirmWithMutatedWriteArgsDoesNotSkipAsk() {
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		ToolUseBlock approved = ToolUseBlock.builder().id("call-write").name("crm_update").input(Map.of("id", 1))
			.build();
		ToolUseBlock mutated = ToolUseBlock.builder().id("call-write").name("crm_update").input(Map.of("id", 2))
			.build();
		RuntimeContext ctx = context("tenant-9", "user-3");
		ctx.put(ConfirmResult.class, new ConfirmResult(true, approved));

		assertEquals(PermissionBehavior.ASK, middleware.checkPermissions(ctx, snapshot("tenant-9", "user-3"), mutated)
			.behavior());
	}

	@Test
	void approvedRedisCredentialSkipsAskOnRetryWithNewCallId() {
		V2ConfirmCredentialStore confirmStore = mock(V2ConfirmCredentialStore.class);
		when(confirmStore.isApproved(any(), any())).thenReturn(true);
		V2ActingPermissionMiddleware gated = new V2ActingPermissionMiddleware(pep, versions, pepProperties,
				confirmStore);
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		ToolUseBlock retry = ToolUseBlock.builder().id("call-write-2").name("crm_update").input(Map.of("id", 1)).build();
		AgentEvent passed = mock(AgentEvent.class);

		AgentEvent got = gated
			.onActing(agent, context("tenant-9", "user-3"), new ActingInput(List.of(retry)), input -> Flux.just(passed))
			.blockFirst();

		assertEquals(passed, got);
		verify(confirmStore).consume(any(), any());
	}

	@Test
	void emitAskPersistsPendingCredential() {
		V2ConfirmCredentialStore confirmStore = mock(V2ConfirmCredentialStore.class);
		V2ActingPermissionMiddleware gated = new V2ActingPermissionMiddleware(pep, versions, pepProperties,
				confirmStore);
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		ToolUseBlock write = ToolUseBlock.builder().id("call-write").name("crm_update").input(Map.of("id", 1)).build();

		gated.onActing(agent, context("tenant-9", "user-3"), new ActingInput(List.of(write)),
				input -> Flux.error(new IllegalStateException("ASK must not execute")))
			.collectList()
			.block();

		verify(confirmStore).savePending(any(), any(), any());
	}

	@Test
	void mixedBatchDeniesInsertAndForwardsRead() {
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.ENFORCE));
		ToolUseBlock insert = tool(AgentModelToolName.DATASOURCE_SKILL_SEARCH, Map.of("sql", "DELETE FROM orders"));
		ToolUseBlock read = ToolUseBlock.builder()
			.id("call-read")
			.name(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH)
			.input(Map.of("query", "q"))
			.build();
		AtomicReference<ActingInput> forwarded = new AtomicReference<>();
		AgentEvent passed = mock(AgentEvent.class);

		List<AgentEvent> events = middleware
			.onActing(agent, context("tenant-9", "user-3"), new ActingInput(List.of(insert, read)), input -> {
				forwarded.set(input);
				return Flux.just(passed);
			})
			.collectList()
			.block();

		assertEquals(1, forwarded.get().toolCalls().size());
		assertEquals(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH, forwarded.get().toolCalls().get(0).getName());
		assertTrue(events.stream().anyMatch(event -> event instanceof ToolResultTextDeltaEvent delta
				&& delta.getDelta().contains("SQL 守卫拒绝写语句")));
		assertTrue(events.contains(passed));
	}

	@Test
	void checkPermissionsSqlWriteIsDenyAndSelectIsAllow() {
		V2RuntimeSnapshot snapshot = snapshot("tenant-9", "user-3");
		RuntimeContext ctx = context("tenant-9", "user-3");
		when(pep.evaluate(any())).thenReturn(decision(true, PepAuthorizationMode.SHADOW));

		assertEquals(PermissionBehavior.DENY,
				middleware.checkPermissions(ctx, snapshot, tool(AgentModelToolName.DATASOURCE_SKILL_SEARCH,
						Map.of("sql", "DELETE FROM orders"))).behavior());
		assertEquals(PermissionBehavior.ALLOW,
				middleware.checkPermissions(ctx, snapshot, tool(AgentModelToolName.DATASOURCE_SKILL_SEARCH,
						Map.of("sql", "SELECT id FROM orders"))).behavior());
	}

	private static ActingInput acting(String toolName, Map<String, Object> input) {
		return new ActingInput(List.of(tool(toolName, input)));
	}

	private static ToolUseBlock tool(String toolName, Map<String, Object> input) {
		return ToolUseBlock.builder().id("call-1").name(toolName).input(input).build();
	}

	private static RuntimeContext context(String tenantId, String userId) {
		return RuntimeContext.builder()
			.sessionId("100")
			.userId(userId)
			.put(V2RuntimeSnapshot.class, snapshot(tenantId, userId))
			.build();
	}

	private static V2RuntimeSnapshot snapshot(String tenantId, String userId) {
		return new V2RuntimeSnapshot(tenantId, tenantId, userId, null, null, null, List.of(), "100", "run-1", "1",
				"DATA_AGENT", 1L, 99L);
	}

	private static PepDecisionResult decision(boolean allowed, PepAuthorizationMode mode) {
		return PepDecisionResult.builder()
			.decisionId("dec-1")
			.subjectKind(SubjectKind.CALLER)
			.decision(AuthorizationDecision.builder()
				.allowed(allowed)
				.reasonCode(allowed ? DecisionReasonCode.POLICY_ALLOWED : DecisionReasonCode.POLICY_DENIED)
				.obligations(List.of())
				.maskFields(List.of())
				.evaluatedAt(Instant.now())
				.build())
			.effectiveMode(mode)
			.evaluatedAt(Instant.now())
			.build();
	}

}
