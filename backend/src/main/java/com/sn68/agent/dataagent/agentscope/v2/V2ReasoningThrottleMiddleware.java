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

import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * v2 thinking 降档兜底中间件：模型在不确定时反复"探索 + 多候选起草"，摇摆轮单轮就能烧掉数千
 * completion token、把 run 拖到 30s+。本中间件在 {@link V2BudgetMiddleware} 的硬预算之外做 run 级
 * 软降档——超过轮次或累计 completion 阈值（任一满足）后，按模型推理协议把本次 ModelCall 的
 * thinking 参数压到最低档，让摇摆轮尽快收敛。
 *
 * <p>阈值是代码常量而非配置键：防 30s+ 摇摆轮的兜底不需要运维调节，且简单问题 1～2 轮自然结束，
 * 前 2 轮不干预，正常问答与工具链路完全无感。协议标志由 {@link HarnessAgentFactory} 挂到
 * RuntimeContext（值为 {@link ModelReasoningProtocol} 的 name()）；信封未绑定或协议缺失/
 * AUTO/NONE 时一律原样放行，不猜测方言。
 */
@Slf4j
public class V2ReasoningThrottleMiddleware implements MiddlewareBase {

	/**
	 * 前 2 轮不干预：轮次超过该值才降档。预算中间件在本中间件外层已调用 {@code beginRound}，
	 * 信封轮次含当前调用，因此第 3 轮起触发。
	 */
	static final int THROTTLE_AFTER_ROUNDS = 2;

	/**
	 * 累计 completion token 超过该值即降档：即便轮次尚浅，前期已烧掉 6k 输出也说明当前轮
	 * 大概率在摇摆起草，提前兜底。
	 */
	static final long THROTTLE_AFTER_COMPLETION_TOKENS = 6000L;

	/** REASONING_EFFORT 协议的降档档位。 */
	static final String THROTTLE_REASONING_EFFORT = "low";

	/** RuntimeContext 中 run 级推理协议标志的 key，值为 {@link ModelReasoningProtocol#name()}。 */
	public static final String REASONING_PROTOCOL_KEY = "v2ReasoningProtocol";

	private static final String THINKING_KEY = "thinking";

	private static final String ENABLE_THINKING_KEY = "enable_thinking";

	@Override
	public int order() {
		// 80 < 预算/压缩中间件的 90：排序后位于预算中间件内层，执行到本中间件时
		// envelope.beginRound 已把当前调用计入，rounds() 的语义即"当前是第几轮"，
		// "轮次>2 降档"与"前 2 轮不干预"严格对应；重写后的 options 继续向内层链与
		// 真正的模型调用透传，不会被外层中间件改写。
		return 80;
	}

	@Override
	public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
			Function<ModelCallInput, Flux<AgentEvent>> next) {
		return next.apply(throttle(ctx, input));
	}

	/**
	 * 超阈值则按协议重建 options 注入降档字段；信息缺失、未超阈值或已降档时原样返回同一个
	 * input（保持引用不变，便于上层与测试判断"未干预"）。
	 */
	ModelCallInput throttle(RuntimeContext ctx, ModelCallInput input) {
		V2BudgetEnvelope envelope = ctx == null ? null : ctx.get(V2BudgetEnvelope.class);
		if (input == null || envelope == null) {
			return input;
		}
		ModelReasoningProtocol protocol = protocol(ctx);
		if (protocol == null || protocol == ModelReasoningProtocol.AUTO || protocol == ModelReasoningProtocol.NONE) {
			// 无协议标志或协议本身不产生 thinking 字段：无操作
			return input;
		}
		int rounds = envelope.rounds();
		long completionTokens = envelope.completionTokens();
		if (rounds <= THROTTLE_AFTER_ROUNDS && completionTokens <= THROTTLE_AFTER_COMPLETION_TOKENS) {
			return input;
		}
		if (alreadyThrottled(input.options(), protocol)) {
			return input;
		}
		log.info("Reasoning throttled. round={}, completionTokens={}, protocol={}", rounds, completionTokens,
				protocol.name());
		return new ModelCallInput(input.messages(), input.tools(), throttledOptions(input.options(), protocol),
				input.model());
	}

	private ModelReasoningProtocol protocol(RuntimeContext ctx) {
		Object raw = ctx.get(REASONING_PROTOCOL_KEY);
		if (raw == null) {
			return null;
		}
		try {
			return ModelReasoningProtocol.valueOf(String.valueOf(raw));
		}
		catch (IllegalArgumentException ex) {
			// 未知协议值按缺失处理，宁可不降档也不注入错误方言的参数
			return null;
		}
	}

	/**
	 * 幂等保护：本次调用的 options 已带降档标记（thinking disabled / enable_thinking=false /
	 * reasoningEffort=low）时不重复注入。
	 */
	private static boolean alreadyThrottled(GenerateOptions options, ModelReasoningProtocol protocol) {
		Map<String, Object> existing = options == null ? null : options.getAdditionalBodyParams();
		return switch (protocol) {
			case THINKING_OBJECT, THINKING_OBJECT_WITH_EFFORT -> hasThinkingDisabled(existing);
			case ENABLE_THINKING, ENABLE_THINKING_ONLY ->
				Boolean.FALSE.equals(existing == null ? null : existing.get(ENABLE_THINKING_KEY));
			case REASONING_EFFORT ->
				options != null && THROTTLE_REASONING_EFFORT.equals(options.getReasoningEffort());
			default -> true;
		};
	}

	private static boolean hasThinkingDisabled(Map<String, Object> bodyParams) {
		if (bodyParams == null || !(bodyParams.get(THINKING_KEY) instanceof Map<?, ?> thinking)) {
			return false;
		}
		return "disabled".equals(thinking.get("type"));
	}

	/**
	 * 重建 options：原字段逐项拷贝到 builder，只覆盖与 thinking 相关的键，其余保持原值。
	 * {@code additionalBodyParams} 先整表拷入再用同名 key 覆盖，保证只动降档涉及的键。
	 */
	private static GenerateOptions throttledOptions(GenerateOptions options, ModelReasoningProtocol protocol) {
		GenerateOptions.Builder builder = copyOptions(options);
		switch (protocol) {
			case THINKING_OBJECT, THINKING_OBJECT_WITH_EFFORT ->
				builder.additionalBodyParam(THINKING_KEY, Map.of("type", "disabled"));
			case ENABLE_THINKING, ENABLE_THINKING_ONLY -> builder.additionalBodyParam(ENABLE_THINKING_KEY, Boolean.FALSE);
			case REASONING_EFFORT -> builder.reasoningEffort(THROTTLE_REASONING_EFFORT);
			default -> {
				return options;
			}
		}
		return builder.build();
	}

	private static GenerateOptions.Builder copyOptions(GenerateOptions options) {
		GenerateOptions.Builder builder = GenerateOptions.builder();
		if (options == null) {
			return builder;
		}
		builder.apiKey(options.getApiKey())
			.baseUrl(options.getBaseUrl())
			.endpointPath(options.getEndpointPath())
			.modelName(options.getModelName())
			.stream(options.getStream())
			.temperature(options.getTemperature())
			.topP(options.getTopP())
			.maxTokens(options.getMaxTokens())
			.maxCompletionTokens(options.getMaxCompletionTokens())
			.frequencyPenalty(options.getFrequencyPenalty())
			.presencePenalty(options.getPresencePenalty())
			.thinkingBudget(options.getThinkingBudget())
			.reasoningEffort(options.getReasoningEffort())
			.executionConfig(options.getExecutionConfig())
			.toolChoice(options.getToolChoice())
			.topK(options.getTopK())
			.seed(options.getSeed())
			.cacheControl(options.getCacheControl())
			.parallelToolCalls(options.getParallelToolCalls())
			.responseFormat(options.getResponseFormat())
			.additionalHeaders(options.getAdditionalHeaders())
			.additionalBodyParams(options.getAdditionalBodyParams())
			.additionalQueryParams(options.getAdditionalQueryParams());
		return builder;
	}

}
