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

import com.sn68.agent.dataagent.constant.AgentStatusConstant;
import com.sn68.agent.dataagent.dto.agent.AgentApiKeyStatusModifyReq;
import com.sn68.agent.dataagent.dto.agent.AgentIdFilterReq;
import com.sn68.agent.dataagent.dto.agent.AgentIdReq;
import com.sn68.agent.dataagent.dto.agent.AgentStatusModifyReq;
import com.sn68.agent.dataagent.dto.agent.DataAgentListQueryReq;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.service.agent.AgentModelConfigService;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.vo.ApiKeyResp;
import com.sn68.agent.dataagent.vo.AgentRunMetaVO;
import com.sn68.agent.dataagent.vo.AgentRunWorkbenchMetaVO;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护 DataAgent 基础信息、运行元数据、状态和 API Key。
 */
@RestController
@RequestMapping("/data-agent")
@AllArgsConstructor
@Tag(name = "DataAgent 管理", description = "维护 DataAgent 基础信息、运行元数据、状态和 API Key")
public class DataAgentManageController {

	private final DataAgentService agentService;

	private final AgentModelConfigService agentModelConfigService;

	@Operation(summary = "查询DataAgent 管理清单", description = "查询DataAgent 管理清单，用于DataAgent 管理相关管理和运行场景。")
	@PostMapping("/query")
	public List<DataAgent> list(@RequestBody(required = false) DataAgentListQueryReq request) {
		return agentService.list(request == null ? null : request.status(), request == null ? null : request.keyword());
	}

	@Operation(summary = "查询DataAgent 管理运行元数据", description = "查询DataAgent 管理运行元数据，用于DataAgent 管理相关管理和运行场景。")
	@PostMapping("/run-meta/query")
	public AgentRunMetaVO runMeta(@Valid @RequestBody AgentIdReq request) {
		DataAgent agent = agentService.requireAgentWithPreview(request.agentId());
		return AgentRunMetaVO.builder()
			.agent(agent)
			.chatModels(agentModelConfigService.listRuntimeChatModels(agent))
			.build();
	}

	@Operation(summary = "查询DataAgent 管理运行工作台元数据", description = "查询DataAgent 管理运行工作台元数据，用于DataAgent 管理相关管理和运行场景。")
	@PostMapping("/run-workbench-meta/query")
	public AgentRunWorkbenchMetaVO runWorkbenchMeta(@RequestBody(required = false) AgentIdFilterReq request) {
		Long agentId = request == null ? null : request.agentId();
		List<DataAgent> availableAgents = agentService.findByStatus(AgentStatusConstant.PUBLISHED);
		DataAgent currentAgent = resolveCurrentAgent(availableAgents, agentId);
		return AgentRunWorkbenchMetaVO.builder()
			.availableAgents(availableAgents)
			.currentAgent(currentAgent)
			.chatModels(currentAgent == null ? Collections.emptyList()
					: agentModelConfigService.listRuntimeChatModels(currentAgent))
			.build();
	}

	@Operation(summary = "查询DataAgent 管理详情", description = "查询DataAgent 管理详情，用于DataAgent 管理相关管理和运行场景。")
	@GetMapping("/{id}/detail")
	public DataAgent get(@PathVariable Long id) {
		return agentService.requireAgentWithPreview(id);
	}

	@Operation(summary = "创建DataAgent 管理", description = "创建DataAgent 管理，用于DataAgent 管理相关管理和运行场景。")
	@AccessLog(module = "DataAgent 管理", description = "创建DataAgent")
	@PostMapping("/create")
	public DataAgent create(@RequestBody DataAgent dataAgent) {
		return agentService.create(dataAgent);
	}

	@Operation(summary = "修改DataAgent 管理", description = "修改DataAgent 管理，用于DataAgent 管理相关管理和运行场景。")
	@AccessLog(module = "DataAgent 管理", description = "修改DataAgent")
	@PutMapping("/{id}/modify")
	public DataAgent update(@PathVariable Long id, @RequestBody DataAgent dataAgent) {
		return agentService.update(id, dataAgent);
	}

	@Operation(summary = "删除DataAgent 管理", description = "删除DataAgent 管理，用于DataAgent 管理相关管理和运行场景。")
	@AccessLog(module = "DataAgent 管理", description = "删除DataAgent")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		agentService.deleteById(id);
	}

	@Operation(summary = "修改DataAgent 管理状态", description = "修改DataAgent 管理状态，用于DataAgent 管理相关管理和运行场景。")
	@AccessLog(module = "DataAgent 管理", description = "发布或下线DataAgent")
	@PutMapping("/{id}/status")
	public DataAgent updateStatus(@PathVariable Long id, @RequestBody AgentStatusModifyReq request) {
		String status = request == null ? null : request.status();
		if (AgentStatusConstant.PUBLISHED.equals(status)) {
			return agentService.publish(id);
		}
		if (AgentStatusConstant.OFFLINE.equals(status)) {
			return agentService.offline(id);
		}
		throw CheckedException.badRequest("Agent状态不合法");
	}

	@Operation(summary = "查询DataAgent 管理API Key", description = "查询DataAgent 管理API Key，用于DataAgent 管理相关管理和运行场景。")
	@GetMapping("/{id}/api-key")
	public ApiKeyResp getApiKey(@PathVariable Long id) {
		return agentService.getApiKey(id);
	}

	@Operation(summary = "创建DataAgent 管理API Key", description = "创建DataAgent 管理API Key，用于DataAgent 管理相关管理和运行场景。")
	// 响应体含明文 API Key，只记录操作痕迹
	@AccessLog(module = "DataAgent 管理", description = "生成DataAgent API Key", response = false)
	@PostMapping("/{id}/api-key/create")
	public ApiKeyResp generateApiKey(@PathVariable Long id) {
		return agentService.generateApiKeyResponse(id);
	}

	@Operation(summary = "修改DataAgent 管理API Key", description = "修改DataAgent 管理API Key，用于DataAgent 管理相关管理和运行场景。")
	// 响应体含明文 API Key，只记录操作痕迹
	@AccessLog(module = "DataAgent 管理", description = "重置DataAgent API Key", response = false)
	@PutMapping("/{id}/api-key/modify")
	public ApiKeyResp resetApiKey(@PathVariable Long id) {
		return agentService.resetApiKeyResponse(id);
	}

	@Operation(summary = "删除DataAgent 管理API Key", description = "删除DataAgent 管理API Key，用于DataAgent 管理相关管理和运行场景。")
	@AccessLog(module = "DataAgent 管理", description = "删除DataAgent API Key", response = false)
	@DeleteMapping("/{id}/api-key")
	public ApiKeyResp deleteApiKey(@PathVariable Long id) {
		return agentService.deleteApiKeyResponse(id);
	}

	@Operation(summary = "修改DataAgent 管理API Key", description = "修改DataAgent 管理API Key，用于DataAgent 管理相关管理和运行场景。")
	@AccessLog(module = "DataAgent 管理", description = "启停DataAgent API Key", response = false)
	@PutMapping("/{id}/api-key/status")
	public ApiKeyResp toggleApiKey(@PathVariable Long id,
			@Valid @RequestBody AgentApiKeyStatusModifyReq request) {
		return agentService.toggleApiKeyResponse(id, request.enabled());
	}

	private DataAgent resolveCurrentAgent(List<DataAgent> availableAgents, Long agentId) {
		if (availableAgents == null || availableAgents.isEmpty()) {
			return null;
		}
		if (agentId != null) {
			return availableAgents.stream()
				.filter(agent -> agentId.equals(agent.getId()))
				.findFirst()
				.orElse(availableAgents.get(0));
		}
		return availableAgents.get(0);
	}

}
