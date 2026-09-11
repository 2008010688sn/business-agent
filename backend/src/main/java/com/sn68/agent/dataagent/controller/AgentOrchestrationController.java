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
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.agent.AgentCollaboratorResp;
import com.sn68.agent.dataagent.dto.agent.AgentCollaboratorDeleteReq;
import com.sn68.agent.dataagent.dto.agent.AgentCollaboratorSaveReq;
import com.sn68.agent.dataagent.dto.agent.AgentIdReq;
import com.sn68.agent.dataagent.dto.agent.OrchestrationRunQueryReq;
import com.sn68.agent.dataagent.dto.agent.OrchestrationRunDetailResp;
import com.sn68.agent.dataagent.dto.agent.OrchestrationTraceResp;
import com.sn68.agent.dataagent.entity.AgentOrchestrationPolicy;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.service.agent.AgentCollaboratorService;
import com.sn68.agent.dataagent.service.agent.AgentOrchestrationPolicyService;
import com.sn68.agent.dataagent.service.agent.AgentOrchestrationService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

/**
 * 维护 Agent 协作者、编排策略、预览和运行 Trace。
 */
@RestController
@RequestMapping("/data-agent/orchestration")
@AllArgsConstructor
@Tag(name = "Agent 协作编排", description = "维护 Agent 协作者、编排策略、预览和运行 Trace")
public class AgentOrchestrationController {

	private final AgentCollaboratorService collaboratorService;

	private final AgentOrchestrationPolicyService policyService;

	private final AgentOrchestrationService orchestrationService;

	@Operation(summary = "查询Agent 协作者清单", description = "按 Agent 查询已配置的协作者列表。")
	@PostMapping("/collaborators/query")
	public List<AgentCollaboratorResp> collaborators(@Valid @RequestBody AgentIdReq request) {
		return collaboratorService.list(request.agentId());
	}

	@Operation(summary = "创建Agent 协作编排", description = "创建Agent 协作编排，用于Agent 协作编排相关管理和运行场景。")
	@AccessLog(module = "Agent 协作编排", description = "创建Agent 协作者")
	@PostMapping("/collaborators/create")
	public AgentCollaboratorResp createCollaborator(@RequestBody AgentCollaboratorSaveReq request) {
		return collaboratorService.create(requireAgentId(request == null ? null : request.agentId()), request);
	}

	@Operation(summary = "修改Agent 协作编排", description = "修改Agent 协作编排，用于Agent 协作编排相关管理和运行场景。")
	@AccessLog(module = "Agent 协作编排", description = "修改Agent 协作者")
	@PutMapping("/collaborators/modify")
	public AgentCollaboratorResp updateCollaborator(@RequestBody AgentCollaboratorSaveReq request) {
		return collaboratorService.update(requireAgentId(request == null ? null : request.agentId()),
				requireId(request == null ? null : request.id()), request);
	}

	@Operation(summary = "删除Agent 协作编排", description = "删除Agent 协作编排，用于Agent 协作编排相关管理和运行场景。")
	@AccessLog(module = "Agent 协作编排", description = "删除Agent 协作者")
	@DeleteMapping("/collaborators")
	public void deleteCollaborator(@RequestBody AgentCollaboratorDeleteReq request) {
		collaboratorService.delete(requireAgentId(request == null ? null : request.agentId()),
				requireId(request == null ? null : request.id()));
	}

	@Operation(summary = "查询Agent 协作编排策略", description = "查询Agent 协作编排策略，用于Agent 协作编排相关管理和运行场景。")
	@PostMapping("/policy/query")
	public AgentOrchestrationPolicy getPolicy(@Valid @RequestBody AgentIdReq request) {
		return policyService.getOrCreate(request.agentId());
	}

	@Operation(summary = "修改Agent 协作编排策略", description = "修改Agent 协作编排策略，用于Agent 协作编排相关管理和运行场景。")
	@AccessLog(module = "Agent 协作编排", description = "修改Agent 协作编排策略")
	@PutMapping("/policy")
	public AgentOrchestrationPolicy updatePolicy(@RequestBody AgentOrchestrationPolicy policy) {
		return policyService.update(requireAgentId(policy == null ? null : policy.getAgentId()), policy);
	}

	@Operation(summary = "查询Agent 协作编排运行记录清单", description = "按 Agent 查询协作编排的历史运行记录列表。")
	@PostMapping("/runs/query")
	public List<AgentOrchestrationRun> runs(@Valid @RequestBody AgentIdReq request) {
		return orchestrationService.listRuns(request.agentId());
	}

	@Operation(summary = "查询Agent 协作编排运行详情", description = "按 runId 查询单次协作编排运行的详情。")
	@PostMapping("/runs/detail/query")
	public OrchestrationRunDetailResp run(@RequestBody OrchestrationRunQueryReq request) {
		return orchestrationService.getRun(requireAgentId(request == null ? null : request.agentId()),
				requireRunId(request == null ? null : request.runId()));
	}

	@Operation(summary = "查询Agent 协作编排历史运行链路", description = "按 runId 查询已落库的协作编排调用链路。")
	@PostMapping("/runs/trace/query")
	public OrchestrationTraceResp runTrace(@RequestBody OrchestrationRunQueryReq request) {
		return orchestrationService.getRunTrace(requireAgentId(request == null ? null : request.agentId()),
				requireRunId(request == null ? null : request.runId()));
	}

	@Operation(summary = "查询Agent 协作编排实时运行链路", description = "按 runtimeRequestId 查询本次请求进行中的协作编排调用链路。")
	@PostMapping("/runtime/trace/query")
	public OrchestrationTraceResp runtimeTrace(@RequestBody OrchestrationRunQueryReq request) {
		return orchestrationService.getRuntimeTrace(requireAgentId(request == null ? null : request.agentId()),
				requireRuntimeRequestId(request == null ? null : request.runtimeRequestId()));
	}

	private Long requireAgentId(Long agentId) {
		if (agentId == null) {
			throw CheckedException.badRequest("agentId不能为空");
		}
		return agentId;
	}

	private Long requireId(Long id) {
		if (id == null) {
			throw CheckedException.badRequest("id不能为空");
		}
		return id;
	}

	private Long requireRunId(Long runId) {
		if (runId == null) {
			throw CheckedException.badRequest("runId不能为空");
		}
		return runId;
	}

	private String requireRuntimeRequestId(String runtimeRequestId) {
		if (!StringUtils.hasText(runtimeRequestId)) {
			throw CheckedException.badRequest("runtimeRequestId不能为空");
		}
		return runtimeRequestId;
	}

}
