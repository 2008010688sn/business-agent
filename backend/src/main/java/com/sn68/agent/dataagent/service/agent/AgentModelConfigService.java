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

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.agent.AgentModelConfigItemResp;
import com.sn68.agent.dataagent.dto.agent.RuntimeChatModelDTO;
import com.sn68.agent.dataagent.dto.agent.UpdateAgentModelConfigReq;
import com.sn68.agent.dataagent.entity.DataAgent;

import java.util.List;

/**
 * Agent模型配置服务契约。
 */
public interface AgentModelConfigService {

	/**
	 * 查询Agent模型配置。
	 */
	List<AgentModelConfigItemResp> listAgentModelConfigs(Long agentId);

	/**
	 * 查询Agent模型配置。
	 */
	List<ModelConfigDTO> listAvailableChatModels(Long agentId);

	/**
	 * 查询Agent模型配置。
	 */
	List<RuntimeChatModelDTO> listRuntimeChatModels(Long agentId);

	/**
	 * 查询Agent模型配置。
	 */
	List<RuntimeChatModelDTO> listRuntimeChatModels(DataAgent dataAgent);

	/**
	 * 保存Agent模型配置。
	 */
	List<AgentModelConfigItemResp> updateAgentModelConfigs(Long agentId, UpdateAgentModelConfigReq request);

	/**
	 * 查询Agent模型配置。
	 */
	ModelConfigDTO resolveChatModelConfig(DataAgent dataAgent, Long selectedChatModelConfigId);

	/**
	 * 校验Agent模型配置。
	 */
	boolean isUserSelectable(Long agentId, Long modelConfigId);

}
