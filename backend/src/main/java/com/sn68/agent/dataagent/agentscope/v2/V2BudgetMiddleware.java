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
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeToolMetrics;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

/**
 * v2 四轴预算与死循环指纹中间件：轮次 / 墙钟 / token / 费用；相同工具+规范化参数超限则可见停流。
 *
 * <p>父 run 与子代理共用 {@link V2BudgetEnvelope}（挂在 {@link RuntimeContext}）。取消由
 * {@link V2TenantGuardMiddleware} 处理。装配：{@code builder.middleware(budgetMiddleware)}。
 */
@Slf4j
public class V2BudgetMiddleware implements MiddlewareBase {

	private static final Duration BUDGET_POLL = Duration.ofMillis(200);

	private final AgentScopeV2Properties properties;

	public V2BudgetMiddleware(AgentScopeV2Properties properties) {
		this.properties = properties == null ? new AgentScopeV2Properties() : properties;
	}

	@Override
	public int order() {
		return 90;
	}

	/**
	 * 将信封挂到当前 {@link RuntimeContext}。已有则复用，子代理拷贝/共用同一 context 即计入父预算。
	 */
	public V2BudgetEnvelope bind(RuntimeContext ctx) {
		if (ctx == null) {
			throw CheckedException.fail("v2 budget requires RuntimeContext");
		}
		V2BudgetEnvelope existing = ctx.get(V2BudgetEnvelope.class);
		if (existing != null) {
			return existing;
		}
		V2BudgetEnvelope created = V2BudgetEnvelope.open(properties, ctx.get(AgentRuntimeDeadline.class),
				ctx.get(V2RequestBudget.class));
		ctx.put(V2BudgetEnvelope.class, created);
		if (ctx.get(AgentRuntimeDeadline.class) == null) {
			ctx.put(AgentRuntimeDeadline.class, created.deadline());
		}
		return created;
	}

	@Override
	public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
			Function<AgentInput, Flux<AgentEvent>> next) {
		V2BudgetEnvelope envelope;
		try {
			envelope = bind(ctx);
			envelope.requireCanStartAgent(extraTokens(ctx), extraRounds(ctx), extraDeadline(ctx));
		}
		catch (RuntimeException ex) {
			interruptQuietly(agent);
			return Flux.error(ex);
		}
		Flux<Long> pulse = Flux.interval(BUDGET_POLL).filter(tick -> envelope.wallClockExpired(extraDeadline(ctx)));
		return next.apply(input)
			.handle((AgentEvent event, SynchronousSink<AgentEvent> sink) -> emitOrStop(agent, envelope, ctx, event,
					sink))
			.takeUntilOther(pulse)
			.concatWith(Mono.defer(() -> stopIfExpired(agent, envelope, ctx)));
	}

	@Override
	public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
			Function<ModelCallInput, Flux<AgentEvent>> next) {
		V2BudgetEnvelope envelope;
		try {
			envelope = bind(ctx);
			envelope.beginRound(extraTokens(ctx), extraRounds(ctx), extraDeadline(ctx));
		}
		catch (RuntimeException ex) {
			interruptQuietly(agent);
			return Flux.error(ex);
		}
		return next.apply(input)
			.handle((AgentEvent event, SynchronousSink<AgentEvent> sink) -> {
				if (event instanceof ModelCallEndEvent end) {
					envelope.recordUsage(end.getUsage());
				}
				emitOrStop(agent, envelope, ctx, event, sink);
			});
	}

	@Override
	public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
			Function<ActingInput, Flux<AgentEvent>> next) {
		try {
			V2BudgetEnvelope envelope = bind(ctx);
			envelope.requireSpendRemaining(extraTokens(ctx), extraDeadline(ctx));
			envelope.recordToolCalls(input == null ? List.of() : input.toolCalls());
		}
		catch (RuntimeException ex) {
			interruptQuietly(agent);
			return Flux.error(ex);
		}
		return next.apply(input);
	}

	private void emitOrStop(Agent agent, V2BudgetEnvelope envelope, RuntimeContext ctx, AgentEvent event,
			SynchronousSink<AgentEvent> sink) {
		if (!envelope.wallClockExpired(extraDeadline(ctx))) {
			sink.next(event);
			return;
		}
		interruptQuietly(agent);
		try {
			envelope.requireSpendRemaining(extraTokens(ctx), extraDeadline(ctx));
			sink.next(event);
		}
		catch (RuntimeException ex) {
			sink.error(ex);
		}
	}

	private Mono<AgentEvent> stopIfExpired(Agent agent, V2BudgetEnvelope envelope, RuntimeContext ctx) {
		if (!envelope.wallClockExpired(extraDeadline(ctx))) {
			return Mono.empty();
		}
		interruptQuietly(agent);
		try {
			envelope.requireSpendRemaining(extraTokens(ctx), extraDeadline(ctx));
			return Mono.empty();
		}
		catch (RuntimeException ex) {
			return Mono.error(ex);
		}
	}

	private static long extraTokens(RuntimeContext ctx) {
		AgentRuntimeToolMetrics metrics = ctx == null ? null : ctx.get(AgentRuntimeToolMetrics.class);
		if (metrics == null) {
			return 0L;
		}
		return Math.max(0L, metrics.promptTokens()) + Math.max(0L, metrics.completionTokens());
	}

	private static int extraRounds(RuntimeContext ctx) {
		AgentRuntimeToolMetrics metrics = ctx == null ? null : ctx.get(AgentRuntimeToolMetrics.class);
		if (metrics == null) {
			return 0;
		}
		return Math.max(metrics.modelCallCount(), metrics.reactIterationCount());
	}

	private static AgentRuntimeDeadline extraDeadline(RuntimeContext ctx) {
		return ctx == null ? null : ctx.get(AgentRuntimeDeadline.class);
	}

	private static void interruptQuietly(Agent agent) {
		if (agent == null) {
			return;
		}
		try {
			agent.interrupt();
		}
		catch (RuntimeException ex) {
			log.debug("v2 budget interrupt skipped: {}", ex.getMessage());
		}
	}

}
