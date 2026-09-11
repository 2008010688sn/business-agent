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
package com.sn68.agent.dataagent.service.agent;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.service.DataAgentService;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 调用入口实现：执行一次 Agent 请求并返回文本或带轨迹的详细结果。
 */
@Service
@AllArgsConstructor
public class AgentInvocationServiceImpl implements AgentInvocationService {

	private final DataAgentService dataAgentService;

	@Override
	public String invoke(AgentRequest request) {
		return dataAgentService.executeAgentOnce(request);
	}

	@Override
	public AgentInvocationResult invokeDetailed(AgentRequest request) {
		AtomicReference<AgentUiMessage> uiMessage = new AtomicReference<>();
		String answer = dataAgentService.executeAgentOnce(request, message -> {
			if (message != null && Objects.equals(request.getRuntimeRequestId(), message.runtimeRequestId())
					&& "agent-ui/v2".equals(message.schemaVersion()) && "skill-flow".equals(message.kind())) {
				uiMessage.set(message);
			}
		});
		if (request.getRoutedSkillExecutionMode() == SkillExecutionMode.FLOW
				&& uiMessage.get() == null) {
			throw new IllegalStateException("FLOW 执行完成但未返回公开交互消息");
		}
		return new AgentInvocationResult(answer, uiMessage.get());
	}

}
