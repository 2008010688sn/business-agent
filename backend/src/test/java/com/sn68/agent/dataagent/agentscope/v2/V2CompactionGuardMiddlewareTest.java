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

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.skill.execution.SkillBusinessContext;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.sn68.agent.framework.commons.security.DataPermission;
import com.sn68.agent.framework.commons.security.DataScopeType;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class V2CompactionGuardMiddlewareTest {

	private static final String FLOW_INSTANCE = "flow-88";

	private static final String TABLE = "oms_order";

	private static final String WORKSPACE = "ws-ev-12";

	private final V2CompactionGuardMiddleware middleware = new V2CompactionGuardMiddleware();

	private final Agent agent = mock(Agent.class);

	@Test
	void onSystemPromptInjectsRuleChannelFromSnapshotNotRetrieval() {
		RuntimeContext ctx = context(p0Snapshot());

		String prompt = middleware.onSystemPrompt(agent, ctx, "base-prompt").block();

		assertTrue(prompt.contains("base-prompt"));
		assertTrue(prompt.contains(V2RuleChannel.MARKER));
		assertTrue(prompt.contains("tenantId=tenant-9"));
		assertTrue(prompt.contains("dataScope=SELF"));
		assertTrue(prompt.contains("flowInstanceId=" + FLOW_INSTANCE));
		assertTrue(prompt.contains(TABLE));
		assertTrue(prompt.contains(WORKSPACE));
		assertTrue(prompt.contains("vector retrieval"));
		assertFalse(prompt.contains("from-header"));
	}

	@Test
	void analysisWorkspaceHandlesArePinnedWhenAttached() {
		V2RuntimeSnapshot snapshot = V2RuntimeSnapshot.from(AgentRequest.builder()
			.tenantIdSnapshot("tenant-9")
			.threadId("100")
			.build()).withAnalysisWorkspaceHandles(List.of(WORKSPACE));

		assertEquals(List.of(WORKSPACE), snapshot.analysisWorkspaceHandles());
		assertTrue(V2RuleChannel.block(snapshot).contains(WORKSPACE));
		assertTrue(V2RuleChannel.retainsP0(V2RuleChannel.block(snapshot), snapshot));
	}

	@Test
	void fromRequestCopiesFlowAndTableWhitelistWithoutVectorLookup() {
		SkillBusinessContext business = new SkillBusinessContext(1L, 2L, "orders",
				List.of(new SkillVersionResources.TableScope(TABLE, List.of("id"))), null, List.of(), null, List.of(), 0,
				0, 0);
		AgentRequest request = AgentRequest.builder()
			.tenantIdSnapshot("tenant-9")
			.flowInstanceId(FLOW_INSTANCE)
			.skillBusinessContext(business)
			.build();

		V2RuntimeSnapshot snapshot = V2RuntimeSnapshot.from(request);

		assertTrue(snapshot.tableWhitelist().contains(TABLE));
		assertEquals(FLOW_INSTANCE, snapshot.flowInstanceId());
		assertTrue(snapshot.analysisWorkspaceHandles().isEmpty());
	}

	@Test
	void p0TokensSurviveFakeCompactionTruncationOfHistory() {
		V2RuntimeSnapshot snapshot = p0Snapshot();
		RuntimeContext ctx = context(snapshot);
		List<Msg> history = new ArrayList<>();
		history.add(new SystemMessage(V2RuleChannel.block(snapshot) + " old-system"));
		history.add(new UserMessage("tenant-9 " + FLOW_INSTANCE + " " + TABLE + " " + WORKSPACE));
		for (int i = 0; i < 20; i++) {
			history.add(new UserMessage("chatter-" + i));
		}
		history.add(new UserMessage("current question"));
		List<Msg> compacted = fakeCompact(history);
		assertFalse(V2RuleChannel.retainsP0(joined(compacted), snapshot));

		AtomicReference<ReasoningInput> seen = new AtomicReference<>();
		AgentEvent event = mock(AgentEvent.class);
		AgentEvent got = middleware
			.onReasoning(agent, ctx, new ReasoningInput(compacted, List.of(), null), input -> {
				seen.set(input);
				return Flux.just(event);
			})
			.blockFirst();

		assertSame(event, got);
		String visible = joined(seen.get().messages());
		assertTrue(visible.contains("current question"));
		assertTrue(V2RuleChannel.retainsP0(visible, snapshot));
		assertEquals(MsgRole.SYSTEM, seen.get().messages().get(0).getRole());
	}

	@Test
	void tenantGuardOnReasoningPinsP0AfterTruncation() {
		V2RuntimeSnapshot snapshot = p0Snapshot();
		RuntimeContext ctx = context(snapshot);
		V2TenantGuardMiddleware tenantGuard = new V2TenantGuardMiddleware(null);
		List<Msg> compacted = fakeCompact(List.of(new UserMessage("only the latest turn")));
		AtomicReference<ReasoningInput> seen = new AtomicReference<>();

		tenantGuard.onReasoning(agent, ctx, new ReasoningInput(compacted, List.of(), null), input -> {
			seen.set(input);
			return Flux.empty();
		}).blockFirst();

		assertTrue(V2RuleChannel.retainsP0(joined(seen.get().messages()), snapshot));
	}

	private static List<Msg> fakeCompact(List<Msg> history) {
		if (history == null || history.isEmpty()) {
			return List.of();
		}
		return List.of(new UserMessage(history.get(history.size() - 1).getTextContent()));
	}

	private static String joined(List<Msg> messages) {
		if (messages == null) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		for (Msg message : messages) {
			if (message != null && message.getTextContent() != null) {
				text.append(message.getTextContent()).append('\n');
			}
		}
		return text.toString();
	}

	private static V2RuntimeSnapshot p0Snapshot() {
		return new V2RuntimeSnapshot("tenant-9", "T9", "user-3", "nick",
				DataPermission.builder().scopeType(DataScopeType.SELF).build(), null, List.of(), "100", "run-1",
				FLOW_INSTANCE, List.of(TABLE), List.of(WORKSPACE));
	}

	private static RuntimeContext context(V2RuntimeSnapshot snapshot) {
		return RuntimeContext.builder()
			.sessionId(snapshot.sessionId())
			.userId(snapshot.userId())
			.put("X-Tenant-Id", "from-header")
			.put("tenantId", "from-header")
			.put(V2RuntimeSnapshot.class, snapshot)
			.build();
	}

}
