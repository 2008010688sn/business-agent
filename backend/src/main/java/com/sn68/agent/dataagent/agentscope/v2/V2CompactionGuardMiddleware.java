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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pins P0 facts into the first SYSTEM message so AgentScope 2.0
 * {@code CompactionMiddleware} cannot drop them.
 *
 * <p>2.0 {@code CompactionConfig} has no string-pin API. Compaction only
 * summarizes non-SYSTEM conversation and always re-prepends the first SYSTEM
 * message — that is the quota this guard occupies. Do not rewrite a compressor.
 *
 * <p>{@code HarnessAgentFactory} enables framework compaction with
 * {@code HarnessAgentFactory#nl2sqlCompaction} and registers this middleware
 * (order {@code 90}, outside default compaction order {@code 1}).
 * {@link V2TenantGuardMiddleware#onReasoning} also applies {@link #pinP0}.
 */
public class V2CompactionGuardMiddleware implements MiddlewareBase {

	@Override
	public int order() {
		return 90;
	}

	@Override
	public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
		return Mono.just(V2RuleChannel.mergeIntoPrompt(currentPrompt, snapshot(ctx)));
	}

	@Override
	public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
			Function<ReasoningInput, Flux<AgentEvent>> next) {
		return next.apply(pinP0(ctx, input));
	}

	static ReasoningInput pinP0(RuntimeContext ctx, ReasoningInput input) {
		if (input == null) {
			return null;
		}
		V2RuntimeSnapshot snapshot = snapshot(ctx);
		String block = V2RuleChannel.block(snapshot);
		if (!StringUtils.hasText(block)) {
			return input;
		}
		List<Msg> original = input.messages();
		List<Msg> messages = original == null ? new ArrayList<>() : new ArrayList<>(original);
		if (!messages.isEmpty() && messages.get(0) != null && messages.get(0).getRole() == MsgRole.SYSTEM) {
			String current = messages.get(0).getTextContent();
			String merged = V2RuleChannel.mergeIntoPrompt(current, snapshot);
			if (merged.equals(current)) {
				return input;
			}
			messages.set(0, new SystemMessage(merged));
		}
		else {
			messages.add(0, new SystemMessage(block));
		}
		return new ReasoningInput(List.copyOf(messages), input.tools(), input.options());
	}

	private static V2RuntimeSnapshot snapshot(RuntimeContext ctx) {
		return ctx == null ? null : ctx.get(V2RuntimeSnapshot.class);
	}

}
