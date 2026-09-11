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
package com.sn68.agent.dataagent.agentscope.runtime;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.service.report.AnalysisReportService;
import io.agentscope.core.hook.Hook;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AgentScope钩子组件，封装 DataAgent 对应业务入口。
 */
@Component
@RequiredArgsConstructor
public class AgentScopeHookFactory {

	private final AnswerTraceExplainStore answerTraceExplainStore;

	private final AnalysisReportService analysisReportService;

	/**
	 * 创建AgentScope钩子。
	 */
	public List<Hook> create(AgentRequest request, @Nullable AgentRuntimeEventPublisher eventPublisher) {
		return create(request, eventPublisher, null, 0, null);
	}

	public List<Hook> create(AgentRequest request, @Nullable AgentRuntimeEventPublisher eventPublisher,
			AgentRuntimeToolMetrics toolMetrics, int maxIterations) {
		return create(request, eventPublisher, toolMetrics, maxIterations, null);
	}

	public List<Hook> create(AgentRequest request, @Nullable AgentRuntimeEventPublisher eventPublisher,
			AgentRuntimeToolMetrics toolMetrics, int maxIterations, java.time.Duration finishBuffer) {
		// SSE 只走 V2EventToAgentResponseMapper；不再装配 AgentScopeStreamingHook。
		// eventPublisher / toolMetrics / maxIterations / finishBuffer 保留签名供调用方兼容。
		List<Hook> hooks = new ArrayList<>();
		HumanFeedbackHook humanFeedbackHook = HumanFeedbackHook.from(request);
		if (humanFeedbackHook != null) {
			hooks.add(humanFeedbackHook);
		}
		LinkContextHook linkContextHook = LinkContextHook.from(request);
		if (linkContextHook != null) {
			hooks.add(linkContextHook);
		}
		return hooks;
	}

	public void emitSearchResultSet(AgentRequest request, @Nullable AgentRuntimeEventPublisher eventPublisher) {
		if (eventPublisher == null || request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		String json = analysisReportService.buildPublicResultSetJson(answerTraceExplainStore
			.getExplain(request.getThreadId(), request.getRuntimeRequestId())
			.orElse(null));
		if (!StringUtils.hasText(json)) {
			return;
		}
		eventPublisher.publish(AgentResponse.builder()
			.agentId(request.getAgentId())
			.threadId(request.getThreadId())
			.nodeName("AgentScopeRuntime")
			.textType(TextType.RESULT_SET)
			.text(json)
			.build());
	}

}
