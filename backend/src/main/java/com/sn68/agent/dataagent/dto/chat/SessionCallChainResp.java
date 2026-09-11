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
package com.sn68.agent.dataagent.dto.chat;

import com.sn68.agent.dataagent.dto.agent.OrchestrationTraceResp;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolStepView;
import com.sn68.agent.dataagent.observability.SessionTraceStore.TraceView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话CallChain数据传输对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "DataAgent 统一调用链路")
public class SessionCallChainResp {

	@Schema(description = "会话ID")
	private String sessionId;

	@Schema(description = "Agent ID")
	private String agentId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "Session trace")
	private TraceView trace;

	@Schema(description = "编排调用链路")
	private OrchestrationTraceResp orchestration;

	@Schema(description = "回答过程解释")
	private AnswerTraceExplainView answerExplain;

	@Schema(description = "工具调用步骤")
	@Builder.Default
	private List<ToolStepView> toolSteps = List.of();

}
