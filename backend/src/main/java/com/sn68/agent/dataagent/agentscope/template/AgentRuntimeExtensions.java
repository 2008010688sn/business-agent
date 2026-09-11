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
package com.sn68.agent.dataagent.agentscope.template;

import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.ToolExecutionContext;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeDeadline;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.time.Duration;

/**
 * 智能体运行时扩展集合：工具箱、记忆、工具执行上下文/配置、钩子、Skill 装配、
 * 迭代与超时预算，以及随运行结束需要统一释放的资源；close() 逐个关闭且单个失败不阻断其余释放。
 */
@Slf4j
public record AgentRuntimeExtensions(Toolkit toolkit, Memory memory, ToolExecutionContext toolExecutionContext,
		ExecutionConfig toolExecutionConfig, List<Hook> hooks, Map<String, Object> attributes, SkillBox skillBox,
		String skillInstructions, List<AutoCloseable> closeables, int maxIterations, Duration modelTimeout,
		Duration finishBuffer, AgentRuntimeDeadline deadline) implements AutoCloseable {

	private static final AgentRuntimeExtensions EMPTY = new AgentRuntimeExtensions(null, null,
			ToolExecutionContext.empty(), null, List.of(), Map.of(), null, "", List.of(), 10,
			Duration.ofSeconds(45), Duration.ofSeconds(2), null);

	public AgentRuntimeExtensions {
		toolExecutionContext = toolExecutionContext == null ? ToolExecutionContext.empty() : toolExecutionContext;
		hooks = hooks == null ? List.of() : List.copyOf(hooks);
		attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
		skillInstructions = skillInstructions == null ? "" : skillInstructions;
		closeables = closeables == null ? List.of() : List.copyOf(closeables);
	}

	public static AgentRuntimeExtensions empty() {
		return EMPTY;
	}

	@Override
	public void close() {
		for (AutoCloseable closeable : closeables) {
			if (closeable == null) {
				continue;
			}
			try {
				closeable.close();
			}
			catch (Exception ex) {
				// Cleanup must not mask the original agent execution result, but a leak must still be visible.
				log.warn("Failed to close an agent runtime resource. resourceType={}", closeable.getClass().getName(), ex);
			}
		}
	}

}
