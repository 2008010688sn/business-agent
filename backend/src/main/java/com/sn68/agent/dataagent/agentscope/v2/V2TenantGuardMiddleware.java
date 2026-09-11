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
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

/**
 * v2 洋葱中间件：从 {@link V2RuntimeSnapshot} 注入租户/红线，并在取消后停流。
 *
 * <p>{@code onActing} 本 PR 透传；PEP/SQL 守卫、四轴预算分别由 PR3/PR2 承接。规则通道与 P0
 * 压缩钉死见 {@link V2RuleChannel} / {@link V2CompactionGuardMiddleware}。
 */
@Slf4j
@RequiredArgsConstructor
public class V2TenantGuardMiddleware implements MiddlewareBase {

	static final String GUARD_MARKER = V2RuleChannel.MARKER;

	private static final Duration CANCEL_POLL = Duration.ofMillis(200);

	private final AgentRuntimeRegistry runtimeRegistry;

	@Override
	public int order() {
		return 100;
	}

	@Override
	public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
		return Mono.just(V2RuleChannel.mergeIntoPrompt(currentPrompt, snapshot(ctx)));
	}

	@Override
	public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
			Function<ReasoningInput, Flux<AgentEvent>> next) {
		return next.apply(V2CompactionGuardMiddleware.pinP0(ctx, input));
	}

	@Override
	public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
			Function<AgentInput, Flux<AgentEvent>> next) {
		if (isCancelled(ctx)) {
			interruptQuietly(agent);
			return Flux.error(new CancellationException("cancelled"));
		}
		Flux<Long> cancelPulse = Flux.interval(CANCEL_POLL).filter(tick -> isCancelled(ctx));
		return next.apply(input)
			.handle((AgentEvent event, SynchronousSink<AgentEvent> sink) -> {
				if (isCancelled(ctx)) {
					interruptQuietly(agent);
					sink.error(new CancellationException("cancelled"));
					return;
				}
				sink.next(event);
			})
			.takeUntilOther(cancelPulse)
			.concatWith(Mono.defer(() -> {
				if (isCancelled(ctx)) {
					interruptQuietly(agent);
					return Mono.<AgentEvent>error(new CancellationException("cancelled"));
				}
				return Mono.<AgentEvent>empty();
			}));
	}

	@Override
	public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
			Function<ActingInput, Flux<AgentEvent>> next) {
		return next.apply(input);
	}

	private boolean isCancelled(RuntimeContext ctx) {
		V2RuntimeSnapshot snapshot = snapshot(ctx);
		if (snapshot == null || runtimeRegistry == null || !StringUtils.hasText(snapshot.sessionId())
				|| !StringUtils.hasText(snapshot.runtimeRequestId())) {
			return false;
		}
		return runtimeRegistry.isCancelled(snapshot.sessionId(), snapshot.runtimeRequestId());
	}

	private static V2RuntimeSnapshot snapshot(RuntimeContext ctx) {
		return ctx == null ? null : ctx.get(V2RuntimeSnapshot.class);
	}

	private static void interruptQuietly(Agent agent) {
		if (agent == null) {
			return;
		}
		try {
			agent.interrupt();
		}
		catch (RuntimeException ex) {
			log.debug("v2 cancel interrupt skipped: {}", ex.getMessage());
		}
	}

	static String guardBlock(V2RuntimeSnapshot snapshot) {
		return V2RuleChannel.block(snapshot);
	}

}
