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
package com.sn68.agent.dataagent.constant;

import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;

/**
 * AgentScope 运行时共享的默认配置常量，集中维护避免散落在多个实现类中。
 */
public final class AgentRuntimeConstant {

	public static final int REACT_AGENT_DEFAULT_MAX_ITERS = 16;

	public static final Duration MODEL_EXECUTION_TIMEOUT = Duration.ofMinutes(2);

	public static final int MODEL_MAX_ATTEMPTS = 2;

	public static final Duration TOOL_EXECUTION_TIMEOUT = Duration.ofSeconds(30);

	public static final int TOOL_MAX_ATTEMPTS = 1;

	public static final ExecutionConfig DEFAULT_MODEL_EXECUTION_CONFIG = ExecutionConfig.builder()
		.timeout(MODEL_EXECUTION_TIMEOUT)
		.maxAttempts(MODEL_MAX_ATTEMPTS)
		.build();

	public static final ExecutionConfig DEFAULT_TOOL_EXECUTION_CONFIG = ExecutionConfig.builder()
		.timeout(TOOL_EXECUTION_TIMEOUT)
		.maxAttempts(TOOL_MAX_ATTEMPTS)
		.build();

	private AgentRuntimeConstant() {

	}

	/**
	 * Agent 整体等待时间相对工具超时时间的缓冲，避免长耗时工具刚完成后父级 Agent 先超时。
	 */
	public static final Duration AGENT_CALL_TOOL_TIMEOUT_BUFFER = Duration.ofSeconds(30);

}
