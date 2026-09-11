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

import com.sn68.agent.dataagent.dto.agent.AgentCollaboratorResp;
import com.sn68.agent.dataagent.dto.agent.AgentCollaboratorSaveReq;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import java.util.List;

/**
 * Agent协作者服务契约。
 */
public interface AgentCollaboratorService {

	/**
	 * 查询Agent协作者。
	 */
	List<AgentCollaboratorResp> list(Long agentId);

	/**
	 * 查询Agent协作者。
	 */
	List<AgentCollaborator> listEnabled(Long agentId);

	/**
	 * 创建Agent协作者。
	 */
	AgentCollaborator create(Long agentId, AgentCollaborator collaborator);

	/**
	 * Create a collaborator from the HTTP write contract.
	 */
	AgentCollaboratorResp create(Long agentId, AgentCollaboratorSaveReq request);

	/**
	 * 保存Agent协作者。
	 */
	AgentCollaborator update(Long agentId, Long id, AgentCollaborator collaborator);

	/**
	 * Update a collaborator from the HTTP write contract.
	 */
	AgentCollaboratorResp update(Long agentId, Long id, AgentCollaboratorSaveReq request);

	/**
	 * 删除Agent协作者。
	 */
	void delete(Long agentId, Long id);

	/**
	 * 校验Agent协作者。
	 */
	AgentCollaborator requireEnabled(Long agentId, Long collaboratorAgentId);

}
