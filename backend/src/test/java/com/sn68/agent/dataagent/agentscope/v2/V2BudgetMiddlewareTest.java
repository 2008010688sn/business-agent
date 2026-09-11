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

import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeDeadline;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeErrorCode;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeToolMetrics;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

class V2BudgetMiddlewareTest {

	private final Agent agent = mock(Agent.class);

	@Test
	void fingerprintNormalizesKeyOrderAndWhitespace() {
		Map<String, Object> left = new LinkedHashMap<>();
		left.put("b", " x ");
		left.put("a", "y");
		Map<String, Object> right = new LinkedHashMap<>();
		right.put("a", "y");
		right.put("b", "x");
		assertEquals(V2BudgetEnvelope.fingerprint("search", left), V2BudgetEnvelope.fingerprint("search", right));
		assertEquals(V2BudgetEnvelope.fingerprint("search", Map.of("q", "  hello  ")),
				V2BudgetEnvelope.fingerprint("search", Map.of("q", "hello")));
	}

	@Test
	void onAgentPassesThroughWhenBudgetRemains() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(30), 1000L, 1000L, 3));
		AgentEvent event = mock(AgentEvent.class);
		RuntimeContext ctx = context();

		AgentEvent got = middleware.onAgent(agent, ctx, new AgentInput(List.of()), input -> Flux.just(event))
			.blockFirst();

		assertSame(event, got);
		verify(agent, never()).interrupt();
	}

	@Test
	void onAgentStopsWhenWallClockExpired() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(30), 1000L, 1000L, 3));
		RuntimeContext ctx = RuntimeContext.builder()
			.put(AgentRuntimeDeadline.class, AgentRuntimeDeadline.startAt(0L, Duration.ofNanos(1)))
			.build();
		AtomicBoolean nextCalled = new AtomicBoolean();

		AgentBudgetExceededException ex = assertThrows(AgentBudgetExceededException.class,
				() -> middleware.onAgent(agent, ctx, new AgentInput(List.of()), input -> {
					nextCalled.set(true);
					return Flux.just(mock(AgentEvent.class));
				}).blockFirst());

		assertFalse(nextCalled.get());
		assertEquals(AgentBudgetExceededException.PHASE_WALL_CLOCK, ex.getPhase());
		assertTrue(ex.getMessage().contains(AgentRuntimeErrorCode.BUDGET_EXCEEDED.getValue()));
		assertTrue(ex.getMessage().contains("时长"));
		verify(agent).interrupt();
	}

	@Test
	void onModelCallStopsAfterMaxItersWithVisibleError() {
		V2BudgetMiddleware middleware = middleware(properties(1, Duration.ofSeconds(30), 1000L, 1000L, 3));
		RuntimeContext ctx = context();
		ModelCallEndEvent event = new ModelCallEndEvent("r1", new ChatUsage(1, 1, 0.01));

		AgentEvent got = middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst();
		assertSame(event, got);

		AtomicBoolean nextCalled = new AtomicBoolean();
		AgentBudgetExceededException ex = assertThrows(AgentBudgetExceededException.class,
				() -> middleware.onModelCall(agent, ctx, modelInput(), input -> {
					nextCalled.set(true);
					return Flux.just(event);
				}).blockFirst());
		assertFalse(nextCalled.get());
		assertEquals(AgentBudgetExceededException.PHASE_ITERATIONS, ex.getPhase());
		assertTrue(ex.getMessage().contains("轮次"));
		verify(agent).interrupt();
	}

	@Test
	void onModelCallStopsWhenTokenBudgetConsumed() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(30), 5L, 1000L, 3));
		RuntimeContext ctx = context();
		ModelCallEndEvent event = new ModelCallEndEvent("r1", new ChatUsage(3, 2, 0.01));

		assertSame(event,
				middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
		assertEquals(5L, ctx.get(V2BudgetEnvelope.class).tokens());

		AtomicBoolean nextCalled = new AtomicBoolean();
		AgentBudgetExceededException ex = assertThrows(AgentBudgetExceededException.class,
				() -> middleware.onModelCall(agent, ctx, modelInput(), input -> {
					nextCalled.set(true);
					return Flux.just(event);
				}).blockFirst());
		assertFalse(nextCalled.get());
		assertEquals(AgentBudgetExceededException.PHASE_TOKEN, ex.getPhase());
		assertTrue(ex.getMessage().contains("token"));
	}

	@Test
	void onModelCallStopsWhenCostBudgetConsumed() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(30), 100_000L, 1L, 3));
		RuntimeContext ctx = context();
		ModelCallEndEvent event = new ModelCallEndEvent("r1", new ChatUsage(10, 0, 0.01));

		assertSame(event,
				middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
		assertTrue(ctx.get(V2BudgetEnvelope.class).costMillicents() >= 1L);

		AgentBudgetExceededException ex = assertThrows(AgentBudgetExceededException.class,
				() -> middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
		assertEquals(AgentBudgetExceededException.PHASE_COST, ex.getPhase());
		assertTrue(ex.getMessage().contains("费用"));
	}

	@Test
	void onActingStopsOnRepeatedFingerprint() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(30), 1000L, 1000L, 3));
		RuntimeContext ctx = context();
		ActingInput first = acting("search", map("b", " x ", "a", "y"));
		ActingInput same = acting("search", map("a", "y", "b", "x"));
		AgentEvent event = mock(AgentEvent.class);

		for (int i = 0; i < 3; i++) {
			assertSame(event, middleware.onActing(agent, ctx, first, input -> Flux.just(event)).blockFirst());
		}

		AtomicBoolean nextCalled = new AtomicBoolean();
		CheckedException ex = assertThrows(CheckedException.class,
				() -> middleware.onActing(agent, ctx, same, input -> {
					nextCalled.set(true);
					return Flux.just(event);
				}).blockFirst());
		assertFalse(nextCalled.get());
		assertTrue(ex.getMessage().contains("死循环"));
		assertTrue(ex.getMessage().contains("search"));
		verify(agent).interrupt();
	}

	@Test
	void subagentSpendCountsInParentEnvelope() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(30), 5L, 1000L, 3));
		RuntimeContext parent = context();
		middleware.bind(parent);
		RuntimeContext child = RuntimeContext.builder().from(parent).build();
		assertSame(parent.get(V2BudgetEnvelope.class), child.get(V2BudgetEnvelope.class));

		ModelCallEndEvent event = new ModelCallEndEvent("r1", new ChatUsage(3, 2, 0.01));
		middleware.onModelCall(agent, child, modelInput(), input -> Flux.just(event)).blockFirst();
		assertEquals(5L, parent.get(V2BudgetEnvelope.class).tokens());

		AgentBudgetExceededException ex = assertThrows(AgentBudgetExceededException.class,
				() -> middleware.onAgent(agent, parent, new AgentInput(List.of()),
						input -> Flux.just(mock(AgentEvent.class))).blockFirst());
		assertTrue(ex.getMessage().contains("token"));
	}

	@Test
	void requestMaxPromptTokensOpensEnvelopeAboveLegacyTwentyThousand() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(30), 20_000L, 10_000L, 3));
		RuntimeContext ctx = RuntimeContext.builder()
			.sessionId("100")
			.userId("user-3")
			.put(V2RequestBudget.class, new V2RequestBudget(30_000L, 0))
			.build();
		ModelCallEndEvent event = new ModelCallEndEvent("r1", new ChatUsage(23_342, 876, 0.01));

		assertSame(event, middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
		V2BudgetEnvelope envelope = ctx.get(V2BudgetEnvelope.class);
		assertEquals(24_218L, envelope.tokens());
		assertEquals(30_000L, envelope.limitTokens());
		assertEquals(15_000L, envelope.limitCost());
		assertTrue(envelope.costMillicents() > 10_000L);
		assertTrue(envelope.costMillicents() < envelope.limitCost());
		assertSame(event, middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
	}

	@Test
	void legacyTwentyThousandStillStopsWithoutRequestBudget() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(30), 20_000L, 10_000L, 3));
		RuntimeContext ctx = context();
		ModelCallEndEvent event = new ModelCallEndEvent("r1", new ChatUsage(23_342, 876, 0.01));

		assertSame(event, middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
		AgentBudgetExceededException ex = assertThrows(AgentBudgetExceededException.class,
				() -> middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
		assertTrue(ex.getMessage().contains("token"));
		assertTrue(ex.getMessage().contains("24218"));
		assertTrue(ex.getMessage().contains("20000"));
	}

	@Test
	void requestMaxItersOverridesV2DefaultEight() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(90), 100_000L, 50_000L, 3));
		RuntimeContext ctx = RuntimeContext.builder()
			.sessionId("100")
			.userId("user-3")
			.put(V2RequestBudget.class, new V2RequestBudget(100_000L, 10))
			.build();
		ModelCallEndEvent event = new ModelCallEndEvent("r1", new ChatUsage(1, 1, 0.01));
		for (int i = 0; i < 8; i++) {
			assertSame(event, middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
		}
		assertEquals(8, ctx.get(V2BudgetEnvelope.class).rounds());
		assertEquals(10, ctx.get(V2BudgetEnvelope.class).limitIters());
		assertSame(event, middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
		assertEquals(9, ctx.get(V2BudgetEnvelope.class).rounds());
	}

	@Test
	void tenWRequestBudgetAllowsBillingSizedTurn() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(90), 20_000L, 10_000L, 3));
		RuntimeContext ctx = RuntimeContext.builder()
			.sessionId("100")
			.userId("user-3")
			.put(V2RequestBudget.class, new V2RequestBudget(100_000L, 10))
			.build();
		ModelCallEndEvent event = new ModelCallEndEvent("r1", new ChatUsage(38_076, 1_203, 0.01));

		assertSame(event, middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
		V2BudgetEnvelope envelope = ctx.get(V2BudgetEnvelope.class);
		assertEquals(39_279L, envelope.tokens());
		assertEquals(100_000L, envelope.limitTokens());
		assertEquals(50_000L, envelope.limitCost());
		assertTrue(envelope.costMillicents() < envelope.limitCost());
		assertSame(event, middleware.onModelCall(agent, ctx, modelInput(), input -> Flux.just(event)).blockFirst());
	}

	@Test
	void existingToolMetricsCountTowardTokenBrake() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(30), 4L, 1000L, 3));
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.recordModelUsage(3L, 1L, 1L);
		RuntimeContext ctx = RuntimeContext.builder().put(AgentRuntimeToolMetrics.class, metrics).build();
		AtomicBoolean nextCalled = new AtomicBoolean();

		AgentBudgetExceededException ex = assertThrows(AgentBudgetExceededException.class,
				() -> middleware.onModelCall(agent, ctx, modelInput(), input -> {
					nextCalled.set(true);
					return Flux.just(new ModelCallEndEvent("r1", new ChatUsage(1, 0, 0.01)));
				}).blockFirst());
		assertFalse(nextCalled.get());
		assertEquals(AgentBudgetExceededException.PHASE_TOKEN, ex.getPhase());
		assertTrue(ex.getMessage().contains("token"));
	}

	@Test
	void bindReusesEnvelopeOnSameContext() {
		V2BudgetMiddleware middleware = middleware(properties(8, Duration.ofSeconds(30), 1000L, 1000L, 3));
		RuntimeContext ctx = context();
		assertSame(middleware.bind(ctx), middleware.bind(ctx));
	}

	private static V2BudgetMiddleware middleware(AgentScopeV2Properties properties) {
		return new V2BudgetMiddleware(properties);
	}

	private static AgentScopeV2Properties properties(int maxIters, Duration maxDuration, long maxTokens, long maxCost,
			int fingerprintRepeatLimit) {
		AgentScopeV2Properties properties = new AgentScopeV2Properties();
		properties.setMaxIters(maxIters);
		properties.setMaxDuration(maxDuration);
		properties.setMaxTokens(maxTokens);
		properties.setMaxCost(maxCost);
		properties.setFingerprintRepeatLimit(fingerprintRepeatLimit);
		return properties;
	}

	private static RuntimeContext context() {
		return RuntimeContext.builder().sessionId("100").userId("user-3").build();
	}

	private static ModelCallInput modelInput() {
		return new ModelCallInput(List.of(), List.of(), null, null);
	}

	private static ActingInput acting(String toolName, Map<String, Object> args) {
		return new ActingInput(List.of(new ToolUseBlock("c1", toolName, args)));
	}

	private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put(k1, v1);
		values.put(k2, v2);
		return values;
	}

}
