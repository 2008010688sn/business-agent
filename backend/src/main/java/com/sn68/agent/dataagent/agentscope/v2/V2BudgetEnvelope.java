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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeDeadline;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 父 run 与子代理共享的四轴预算信封。花费（轮次 / token / 费用 / 指纹）计入同一信封，不另建表。
 */
public final class V2BudgetEnvelope {

	/**
	 * 无定价表时的保守费率：$5 / 1M tokens，单位 millicents。
	 */
	static final long COST_PER_MILLION_TOKENS_MILLICENTS = 500_000L;

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final AgentScopeV2Properties properties;

	private final AgentRuntimeDeadline deadline;

	private final int limitIters;

	private final long limitTokens;

	private final long limitCost;

	private final AtomicInteger rounds = new AtomicInteger();

	private final AtomicLong tokens = new AtomicLong();

	private final AtomicLong completionTokens = new AtomicLong();

	private final AtomicLong costMillicents = new AtomicLong();

	private final ConcurrentHashMap<String, AtomicInteger> fingerprints = new ConcurrentHashMap<>();

	private V2BudgetEnvelope(AgentScopeV2Properties properties, AgentRuntimeDeadline deadline, int limitIters,
			long limitTokens, long limitCost) {
		this.properties = properties;
		this.deadline = deadline;
		this.limitIters = limitIters > 0 ? limitIters : properties.resolvedMaxIters();
		this.limitTokens = limitTokens;
		this.limitCost = limitCost;
	}

	public static V2BudgetEnvelope open(AgentScopeV2Properties properties, AgentRuntimeDeadline existing) {
		return open(properties, existing, null);
	}

	public static V2BudgetEnvelope open(AgentScopeV2Properties properties, AgentRuntimeDeadline existing,
			V2RequestBudget requestBudget) {
		AgentScopeV2Properties props = properties == null ? new AgentScopeV2Properties() : properties;
		Duration max = props.resolvedMaxDuration();
		AgentRuntimeDeadline bound = existing == null ? AgentRuntimeDeadline.start(max) : existing.capFromStart(max);
		int iters = requestBudget != null && requestBudget.hasIterLimit() ? requestBudget.maxIters()
				: props.resolvedMaxIters();
		long tokens = requestBudget != null && requestBudget.hasTokenLimit() ? requestBudget.maxTokens()
				: props.resolvedMaxTokens();
		long cost = requestBudget != null && requestBudget.hasTokenLimit() ? costMillicentsForTokens(tokens)
				: props.resolvedMaxCost();
		return new V2BudgetEnvelope(props, bound, iters, tokens, cost);
	}

	static long costMillicentsForTokens(long tokens) {
		if (tokens <= 0L) {
			return 1L;
		}
		return Math.max(1L, tokens * COST_PER_MILLION_TOKENS_MILLICENTS / 1_000_000L);
	}

	public AgentRuntimeDeadline deadline() {
		return deadline;
	}

	public int rounds() {
		return rounds.get();
	}

	public long tokens() {
		return tokens.get();
	}

	/**
	 * 已完成模型调用累计的 completion（输出）tokens，只读快照，供 thinking 降档兜底判断摇摆轮。
	 */
	public long completionTokens() {
		return completionTokens.get();
	}

	public long costMillicents() {
		return costMillicents.get();
	}

	long limitTokens() {
		return limitTokens;
	}

	long limitCost() {
		return limitCost;
	}

	public boolean wallClockExpired(AgentRuntimeDeadline extraDeadline) {
		return !canStart(deadline) || !canStart(extraDeadline);
	}

	void requireSpendRemaining(long extraTokens, AgentRuntimeDeadline extraDeadline) {
		if (wallClockExpired(extraDeadline)) {
			throw AgentBudgetExceededException.wallClock(rounds.get(), elapsedMs());
		}
		long usedTokens = Math.max(tokens.get(), Math.max(0L, extraTokens));
		if (usedTokens >= limitTokens) {
			throw AgentBudgetExceededException.tokens(rounds.get(), elapsedMs(), usedTokens, limitTokens);
		}
		long usedCost = costMillicents.get();
		if (usedCost >= limitCost) {
			throw AgentBudgetExceededException.cost(rounds.get(), elapsedMs(), usedCost, limitCost);
		}
	}

	void requireCanStartAgent(long extraTokens, int extraRounds, AgentRuntimeDeadline extraDeadline) {
		requireSpendRemaining(extraTokens, extraDeadline);
		int usedRounds = Math.max(rounds.get(), Math.max(0, extraRounds));
		if (usedRounds >= limitIters) {
			throw AgentBudgetExceededException.iterations(usedRounds, elapsedMs(), limitIters);
		}
	}

	void beginRound(long extraTokens, int extraRounds, AgentRuntimeDeadline extraDeadline) {
		requireSpendRemaining(extraTokens, extraDeadline);
		int usedRounds = Math.max(rounds.get(), Math.max(0, extraRounds));
		if (usedRounds >= limitIters) {
			throw AgentBudgetExceededException.iterations(usedRounds, elapsedMs(), limitIters);
		}
		int next = rounds.incrementAndGet();
		if (next > limitIters) {
			throw AgentBudgetExceededException.iterations(next - 1, elapsedMs(), limitIters);
		}
	}

	int limitIters() {
		return limitIters;
	}

	void recordUsage(ChatUsage usage) {
		if (usage == null) {
			return;
		}
		long output = Math.max(0L, usage.getOutputTokens());
		long total = Math.max(0L, usage.getInputTokens()) + output;
		if (total <= 0L) {
			return;
		}
		tokens.addAndGet(total);
		completionTokens.addAndGet(output);
		long addCost = Math.max(1L, total * COST_PER_MILLION_TOKENS_MILLICENTS / 1_000_000L);
		costMillicents.addAndGet(addCost);
	}

	void recordToolCalls(List<ToolUseBlock> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return;
		}
		int limit = properties.resolvedFingerprintRepeatLimit();
		for (ToolUseBlock call : toolCalls) {
			if (call == null) {
				continue;
			}
			String toolName = call.getName() == null ? "" : call.getName();
			int count = fingerprints.computeIfAbsent(fingerprint(toolName, call.getInput()),
					ignored -> new AtomicInteger()).incrementAndGet();
			if (count > limit) {
				throw loopExceeded(toolName, count, limit);
			}
		}
	}

	public static String fingerprint(String toolName, Map<String, Object> args) {
		String name = toolName == null ? "" : toolName.trim();
		return name + '\n' + canonicalJson(args);
	}

	static String canonicalJson(Object args) {
		if (args == null) {
			return "{}";
		}
		try {
			return canonicalNode(OBJECT_MAPPER.valueToTree(args)).toString();
		}
		catch (RuntimeException ex) {
			return String.valueOf(args).trim().replaceAll("\\s+", " ");
		}
	}

	private static JsonNode canonicalNode(JsonNode node) {
		if (node == null || node.isNull()) {
			return OBJECT_MAPPER.getNodeFactory().nullNode();
		}
		if (node.isTextual()) {
			return TextNode.valueOf(node.asText().trim());
		}
		if (node.isArray()) {
			ArrayNode array = OBJECT_MAPPER.createArrayNode();
			node.forEach(item -> array.add(canonicalNode(item)));
			return array;
		}
		if (!node.isObject()) {
			return node;
		}
		ObjectNode object = OBJECT_MAPPER.createObjectNode();
		ArrayList<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		Collections.sort(names);
		for (String name : names) {
			String key = name == null ? "" : name.trim();
			object.set(key, canonicalNode(node.get(name)));
		}
		return object;
	}

	private static boolean canStart(AgentRuntimeDeadline candidate) {
		return candidate == null || candidate.canStart(Duration.ZERO);
	}

	private long elapsedMs() {
		return deadline.elapsedMs();
	}

	private static CheckedException loopExceeded(String toolName, int current, int limit) {
		return CheckedException.fail(
				"相同工具与参数已重复 %d 次，已停止以防死循环: tool=%s, limit=%d".formatted(current, toolName, limit));
	}

}
