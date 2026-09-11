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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2ReasoningThrottleMiddlewareTest {

	private final V2ReasoningThrottleMiddleware middleware = new V2ReasoningThrottleMiddleware();

	@Test
	void thinkingObjectProtocolInjectsDisabledThinkingObject() {
		ModelCallInput input = modelInput(GenerateOptions.builder().modelName("qwen3-max").temperature(0.7).build());
		RuntimeContext ctx = context("THINKING_OBJECT", envelope(3, 0L));

		ModelCallInput throttled = middleware.throttle(ctx, input);

		assertNotNull(throttled);
		assertNotSame(input, throttled);
		Map<String, Object> bodyParams = throttled.options().getAdditionalBodyParams();
		assertEquals(Map.of("type", "disabled"), bodyParams.get("thinking"));
		// 只覆盖 thinking 相关键，其它字段保持原值
		assertEquals("qwen3-max", throttled.options().getModelName());
		assertEquals(0.7, throttled.options().getTemperature());
		assertNullAdditionalFields(input.options().getAdditionalBodyParams());
	}

	@Test
	void thinkingObjectWithEffortProtocolInjectsDisabledThinkingObject() {
		ModelCallInput input = modelInput(GenerateOptions.builder().build());
		RuntimeContext ctx = context("THINKING_OBJECT_WITH_EFFORT", envelope(3, 0L));

		Map<String, Object> bodyParams = middleware.throttle(ctx, input).options().getAdditionalBodyParams();

		assertEquals(Map.of("type", "disabled"), bodyParams.get("thinking"));
	}

	@Test
	void enableThinkingProtocolInjectsFalseFlag() {
		ModelCallInput input = modelInput(GenerateOptions.builder().build());
		RuntimeContext ctx = context("ENABLE_THINKING", envelope(3, 0L));

		Map<String, Object> bodyParams = middleware.throttle(ctx, input).options().getAdditionalBodyParams();

		assertEquals(Boolean.FALSE, bodyParams.get("enable_thinking"));
	}

	@Test
	void enableThinkingOnlyProtocolInjectsFalseFlag() {
		ModelCallInput input = modelInput(GenerateOptions.builder().build());
		RuntimeContext ctx = context("ENABLE_THINKING_ONLY", envelope(3, 0L));

		Map<String, Object> bodyParams = middleware.throttle(ctx, input).options().getAdditionalBodyParams();

		assertEquals(Boolean.FALSE, bodyParams.get("enable_thinking"));
	}

	@Test
	void reasoningEffortProtocolDowngradesToLow() {
		ModelCallInput input = modelInput(GenerateOptions.builder().reasoningEffort("high").build());
		RuntimeContext ctx = context("REASONING_EFFORT", envelope(3, 0L));

		GenerateOptions options = middleware.throttle(ctx, input).options();

		assertEquals("low", options.getReasoningEffort());
		Map<String, Object> bodyParams = options.getAdditionalBodyParams();
		// REASONING_EFFORT 协议不注入 body 参数
		assertTrue(bodyParams == null || (!bodyParams.containsKey("thinking")
				&& !bodyParams.containsKey("enable_thinking")));
	}

	@Test
	void noneAndAutoAndUnknownProtocolsPassThrough() {
		ModelCallInput input = modelInput(GenerateOptions.builder().build());
		assertSame(input, middleware.throttle(context("NONE", envelope(9, 99_999L)), input));
		assertSame(input, middleware.throttle(context("AUTO", envelope(9, 99_999L)), input));
		assertSame(input, middleware.throttle(context("NOT_A_PROTOCOL", envelope(9, 99_999L)), input));
		assertSame(input, middleware.throttle(context(null, envelope(9, 99_999L)), input));
	}

	@Test
	void passesThroughWhenEnvelopeMissing() {
		ModelCallInput input = modelInput(GenerateOptions.builder().build());
		RuntimeContext ctx = RuntimeContext.builder()
			.put(V2ReasoningThrottleMiddleware.REASONING_PROTOCOL_KEY, "THINKING_OBJECT")
			.build();

		assertSame(input, middleware.throttle(ctx, input));
		assertSame(input, middleware.throttle(null, input));
	}

	@Test
	void roundBoundaryTwoDoesNotThrottleAndThreeThrottles() {
		ModelCallInput input = modelInput(GenerateOptions.builder().build());
		RuntimeContext below = context("REASONING_EFFORT", envelope(2, 0L));
		RuntimeContext above = context("REASONING_EFFORT", envelope(3, 0L));

		assertSame(input, middleware.throttle(below, input));
		assertEquals("low", middleware.throttle(above, input).options().getReasoningEffort());
	}

	@Test
	void completionTokenBoundaryFiveNineNineNineDoesNotThrottleAndSixThousandOneThrottles() {
		ModelCallInput input = modelInput(GenerateOptions.builder().build());
		RuntimeContext below = context("THINKING_OBJECT", envelope(1, 5_999L));
		RuntimeContext above = context("THINKING_OBJECT", envelope(1, 6_001L));

		assertSame(input, middleware.throttle(below, input));
		assertEquals(Map.of("type", "disabled"),
				middleware.throttle(above, input).options().getAdditionalBodyParams().get("thinking"));
	}

	@Test
	void alreadyThrottledOptionsAreNotInjectedTwice() {
		RuntimeContext ctx = context("THINKING_OBJECT", envelope(3, 0L));
		ModelCallInput alreadyDisabled = modelInput(GenerateOptions.builder()
			.additionalBodyParams(Map.of("thinking", Map.of("type", "disabled")))
			.build());
		assertSame(alreadyDisabled, middleware.throttle(ctx, alreadyDisabled));

		ModelCallInput alreadyFlagged = modelInput(GenerateOptions.builder()
			.additionalBodyParams(Map.of("enable_thinking", Boolean.FALSE))
			.build());
		RuntimeContext enableCtx = context("ENABLE_THINKING", envelope(3, 0L));
		assertSame(alreadyFlagged, middleware.throttle(enableCtx, alreadyFlagged));

		ModelCallInput alreadyLow = modelInput(GenerateOptions.builder().reasoningEffort("low").build());
		RuntimeContext effortCtx = context("REASONING_EFFORT", envelope(3, 0L));
		assertSame(alreadyLow, middleware.throttle(effortCtx, alreadyLow));
	}

	@Test
	void passThroughKeepsSameInputInstanceWhenBelowThresholds() {
		ModelCallInput input = modelInput(GenerateOptions.builder().modelName("deepseek-r1").build());
		RuntimeContext ctx = context("THINKING_OBJECT", envelope(1, 0L));

		assertSame(input, middleware.throttle(ctx, input));
	}

	private RuntimeContext context(String protocol, V2BudgetEnvelope envelope) {
		RuntimeContext.Builder builder = RuntimeContext.builder().put(V2BudgetEnvelope.class, envelope);
		if (protocol != null) {
			builder.put(V2ReasoningThrottleMiddleware.REASONING_PROTOCOL_KEY, protocol);
		}
		return builder.build();
	}

	private V2BudgetEnvelope envelope(int rounds, long completionTokens) {
		AgentScopeV2Properties properties = new AgentScopeV2Properties();
		properties.setMaxIters(16);
		properties.setMaxDuration(Duration.ofSeconds(90));
		properties.setMaxTokens(200_000L);
		properties.setMaxCost(100_000L);
		properties.setFingerprintRepeatLimit(3);
		V2BudgetEnvelope envelope = V2BudgetEnvelope.open(properties, null);
		for (int i = 0; i < rounds; i++) {
			envelope.beginRound(0L, 0, null);
		}
		if (completionTokens > 0L) {
			envelope.recordUsage(new ChatUsage(1, (int) Math.min(completionTokens, 99_999), 0.01));
		}
		return envelope;
	}

	private ModelCallInput modelInput(GenerateOptions options) {
		return new ModelCallInput(List.of(), List.of(), options, null);
	}

	private void assertNullAdditionalFields(Map<String, Object> bodyParams) {
		assertTrue(bodyParams == null || bodyParams.isEmpty());
	}

}
