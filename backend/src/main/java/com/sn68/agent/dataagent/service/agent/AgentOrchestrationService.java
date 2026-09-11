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
import com.sn68.agent.dataagent.dto.agent.CollaborateToolReq;
import com.sn68.agent.dataagent.dto.agent.CollaborateToolResult;
import com.sn68.agent.dataagent.dto.agent.OrchestrationRunDetailResp;
import com.sn68.agent.dataagent.dto.agent.OrchestrationTraceResp;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import java.util.List;

/**
 * Agent编排服务契约。
 */
public interface AgentOrchestrationService {

	/**
	 * 处理Agent编排。
	 */
	CollaborateToolResult collaborate(AgentRequest orchestratorRequest, CollaborateToolReq request);

	/**
	 * 查询Agent编排。
	 */
	List<AgentOrchestrationRun> listRuns(Long agentId);

	/**
	 * 查询Agent编排。
	 */
	OrchestrationRunDetailResp getRun(Long agentId, Long runId);

	/**
	 * 查询Agent编排。
	 */
	OrchestrationTraceResp getRunTrace(Long agentId, Long runId);

	/**
	 * 查询Agent编排。
	 */
	OrchestrationTraceResp getRuntimeTrace(Long agentId, String runtimeRequestId);

}
