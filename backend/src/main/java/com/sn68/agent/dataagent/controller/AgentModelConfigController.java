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

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.agent.AgentIdReq;
import com.sn68.agent.dataagent.dto.agent.AgentModelConfigItemResp;
import com.sn68.agent.dataagent.dto.agent.UpdateAgentModelConfigReq;
import com.sn68.agent.dataagent.service.agent.AgentModelConfigService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护 Agent 可用模型、默认模型和用户可选模型。
 */
@RestController
@RequestMapping("/data-agent/model-configs")
@AllArgsConstructor
@Tag(name = "Agent 模型配置", description = "维护 Agent 可用模型、默认模型和用户可选模型")
public class AgentModelConfigController {

	private final AgentModelConfigService agentModelConfigService;

	@Operation(summary = "查询Agent 模型配置清单", description = "查询Agent 模型配置清单，用于Agent 模型配置相关管理和运行场景。")
	@PostMapping("/available-models/query")
	public List<ModelConfigDTO> availableModels(@Valid @RequestBody AgentIdReq request) {
		return agentModelConfigService.listAvailableChatModels(request.agentId());
	}

	@Operation(summary = "查询Agent 模型配置清单", description = "查询Agent 模型配置清单，用于Agent 模型配置相关管理和运行场景。")
	@PostMapping("/query")
	public List<AgentModelConfigItemResp> list(@Valid @RequestBody AgentIdReq request) {
		return agentModelConfigService.listAgentModelConfigs(request.agentId());
	}

	@Operation(summary = "修改Agent 模型配置", description = "修改Agent 模型配置，用于Agent 模型配置相关管理和运行场景。")
	@AccessLog(module = "Agent 模型配置", description = "修改Agent 模型绑定")
	@PutMapping
	public List<AgentModelConfigItemResp> update(@Valid @RequestBody UpdateAgentModelConfigReq request) {
		return agentModelConfigService.updateAgentModelConfigs(request.agentId(), request);
	}

}
