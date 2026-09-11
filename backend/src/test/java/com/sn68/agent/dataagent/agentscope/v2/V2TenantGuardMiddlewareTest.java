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

import com.sn68.agent.dataagent.agentscope.session.AgentRuntimeRegistry;
import com.sn68.agent.framework.commons.security.DataPermission;
import com.sn68.agent.framework.commons.security.DataScopeType;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class V2TenantGuardMiddlewareTest {

	private final AgentRuntimeRegistry registry = new AgentRuntimeRegistry();

	private final V2TenantGuardMiddleware middleware = new V2TenantGuardMiddleware(registry);

	private final Agent agent = mock(Agent.class);

	@Test
	void onSystemPromptInjectsTenantFromSnapshotNotHeaders() {
		V2RuntimeSnapshot snapshot = new V2RuntimeSnapshot("tenant-9", "T9", "user-3", "nick",
				DataPermission.builder().scopeType(DataScopeType.SELF).build(), null, List.of(), "100", "run-1", null,
				null, null, null);
		RuntimeContext ctx = RuntimeContext.builder()
			.sessionId("100")
			.userId("user-3")
			.put("X-Tenant-Id", "from-header")
			.put("tenantId", "from-header")
			.put(V2RuntimeSnapshot.class, snapshot)
			.build();

		String prompt = middleware.onSystemPrompt(agent, ctx, "base-prompt").block();

		assertTrue(prompt.contains("base-prompt"));
		assertTrue(prompt.contains(V2TenantGuardMiddleware.GUARD_MARKER));
		assertTrue(prompt.contains("tenantId=tenant-9"));
		assertTrue(prompt.contains("dataScope=SELF"));
		assertFalse(prompt.contains("from-header"));
	}

	@Test
	void onSystemPromptDoesNotInventTenantFromHeadersWhenSnapshotMissing() {
		RuntimeContext ctx = RuntimeContext.builder()
			.put("X-Tenant-Id", "from-header")
			.put("tenantId", "from-header")
			.build();

		String prompt = middleware.onSystemPrompt(agent, ctx, "base-prompt").block();

		assertEquals("base-prompt", prompt);
		assertFalse(prompt.contains("from-header"));
		assertFalse(prompt.contains(V2TenantGuardMiddleware.GUARD_MARKER));
	}

	@Test
	void onAgentStopsWhenRegistryMarksCancelled() {
		registry.register("100", "run-1");
		registry.markCancelled("100", "run-1");
		AtomicBoolean nextCalled = new AtomicBoolean();
		RuntimeContext ctx = context("tenant-9", "user-3", "100", "run-1");

		assertThrows(CancellationException.class,
				() -> middleware
					.onAgent(agent, ctx, new AgentInput(List.of()), input -> {
						nextCalled.set(true);
						return Flux.just(mock(AgentEvent.class));
					})
					.blockFirst());
		assertFalse(nextCalled.get());
		verify(agent).interrupt();
	}

	@Test
	void onAgentPassesThroughWhenNotCancelled() {
		registry.register("100", "run-1");
		AgentEvent event = mock(AgentEvent.class);
		RuntimeContext ctx = context("tenant-9", "user-3", "100", "run-1");

		AgentEvent got = middleware.onAgent(agent, ctx, new AgentInput(List.of()), input -> Flux.just(event))
			.blockFirst();

		assertSame(event, got);
		verify(agent, never()).interrupt();
	}

	@Test
	void onAgentStopsInFlightWhenCancelled() {
		registry.register("100", "run-1");
		RuntimeContext ctx = context("tenant-9", "user-3", "100", "run-1");
		Flux<AgentEvent> events = middleware.onAgent(agent, ctx, new AgentInput(List.of()), input -> Flux.defer(() -> {
			registry.markCancelled("100", "run-1");
			return Flux.never();
		}));

		assertThrows(CancellationException.class, () -> events.blockFirst(Duration.ofSeconds(2)));
		verify(agent).interrupt();
	}

	@Test
	void onActingIsPassThrough() {
		AgentEvent event = mock(AgentEvent.class);
		ActingInput acting = new ActingInput(List.of());

		AgentEvent got = middleware.onActing(agent, context("t1", "u1", "100", "run-1"), acting,
				input -> Flux.just(event)).blockFirst();

		assertSame(event, got);
	}

	private static RuntimeContext context(String tenantId, String userId, String sessionId, String runtimeRequestId) {
		V2RuntimeSnapshot snapshot = new V2RuntimeSnapshot(tenantId, tenantId, userId, null, null, null, List.of(),
				sessionId, runtimeRequestId, null, null, null, null);
		return RuntimeContext.builder()
			.sessionId(sessionId)
			.userId(userId)
			.put(V2RuntimeSnapshot.class, snapshot)
			.build();
	}

}
