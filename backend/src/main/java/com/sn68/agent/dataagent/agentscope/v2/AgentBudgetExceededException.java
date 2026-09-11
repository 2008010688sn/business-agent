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

import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeErrorCode;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.Getter;

/**
 * v2 预算到点异常（墙钟 / token / 费用 / 轮次）。
 *
 * <p>区别于普通运行失败：运行时捕获后若已有流式正文，可把已产出内容作为部分答案优雅收口，
 * 而不是把整轮标记为 FAILED。message 保留 {@code agent_runtime_budget_exceeded} 前缀，
 * 以维持 {@code AgentRuntimeErrorClassifier} 对预算错误的既有分类行为。
 */
@Getter
public class AgentBudgetExceededException extends CheckedException {

	public static final String PHASE_WALL_CLOCK = "WALL_CLOCK";

	public static final String PHASE_TOKEN = "TOKEN";

	public static final String PHASE_COST = "COST";

	public static final String PHASE_ITERATIONS = "ITERATIONS";

	private final String phase;

	private final int completedRounds;

	private final long elapsedMs;

	private AgentBudgetExceededException(String phase, int completedRounds, long elapsedMs, String detail) {
		super(AgentRuntimeErrorCode.BUDGET_EXCEEDED.getValue() + ": " + detail);
		this.phase = phase;
		this.completedRounds = completedRounds;
		this.elapsedMs = elapsedMs;
	}

	public static AgentBudgetExceededException wallClock(int completedRounds, long elapsedMs) {
		return new AgentBudgetExceededException(PHASE_WALL_CLOCK, completedRounds, Math.max(0L, elapsedMs),
				"已达时长预算上限（已完成 %d 轮，耗时 %d s）".formatted(completedRounds, Math.max(0L, elapsedMs) / 1000L));
	}

	public static AgentBudgetExceededException tokens(int completedRounds, long elapsedMs, long usedTokens,
			long limitTokens) {
		return new AgentBudgetExceededException(PHASE_TOKEN, completedRounds, Math.max(0L, elapsedMs),
				"已达 token 预算上限（已完成 %d 轮，累计 %d / %d tokens）".formatted(completedRounds, usedTokens,
						limitTokens));
	}

	public static AgentBudgetExceededException cost(int completedRounds, long elapsedMs, long usedCostMillicents,
			long limitCostMillicents) {
		return new AgentBudgetExceededException(PHASE_COST, completedRounds, Math.max(0L, elapsedMs),
				"已达费用预算上限（已完成 %d 轮，累计 %d / %d millicents）".formatted(completedRounds, usedCostMillicents,
						limitCostMillicents));
	}

	public static AgentBudgetExceededException iterations(int completedRounds, long elapsedMs, int limitIters) {
		return new AgentBudgetExceededException(PHASE_ITERATIONS, completedRounds, Math.max(0L, elapsedMs),
				"已达轮次预算上限（已完成 %d 轮，上限 %d 轮）".formatted(completedRounds, limitIters));
	}

}
