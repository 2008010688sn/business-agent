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
package com.sn68.agent.dataagent.service.report;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.service.agent.AgentModelConfigService;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 使用当前 Agent 的对话模型生成报告，并纳入统一 Token 用量统计。
 */
@Service
@RequiredArgsConstructor
public class AnalysisReportModelService {

	private final DataAgentService agentService;

	private final AgentModelConfigService agentModelConfigService;

	private final DynamicModelFactory dynamicModelFactory;

	private final AgentTokenUsageService tokenUsageService;

	public String generate(String prompt, AnswerTraceExplainView explain) {
		if (explain == null || !StringUtils.isNumeric(explain.getAgentId())) {
			throw new IllegalStateException("报告缺少可用的 Agent 模型上下文");
		}
		Long agentId = Long.valueOf(explain.getAgentId());
		DataAgent agent = agentService.requireAgent(agentId);
		ModelConfigDTO modelConfig = agentModelConfigService.resolveChatModelConfig(agent,
				explain.getChatModelConfigId());
		AgentRequest request = AgentRequest.builder()
			.agentId(explain.getAgentId())
			.agentNameSnapshot(explain.getAgentName())
			.threadId(explain.getSessionId())
			.runtimeRequestId(explain.getRuntimeRequestId())
			.rootRuntimeRequestId(explain.getRootRuntimeRequestId())
			.parentRuntimeRequestId(explain.getParentRuntimeRequestId())
			.chatModelConfigId(explain.getChatModelConfigId())
			.tenantIdSnapshot(explain.getTenantId())
			.tenantCodeSnapshot(explain.getTenantCode())
			.userIdSnapshot(explain.getUserId())
			.userNickNameSnapshot(explain.getUserNickName())
			.requestSource(explain.getRequestSource())
			.build();
		return tokenUsageService.callAndRecord(dynamicModelFactory.createChatModel(modelConfig), prompt,
				tokenUsageService.buildContext(request, modelConfig, AgentTokenUsageService.SOURCE_ANALYSIS_REPORT));
	}

}
