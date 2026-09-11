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
package com.sn68.agent.dataagent.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.tool.ToolPageQueryReq;
import com.sn68.agent.dataagent.dto.tool.ToolResourceDTO;
import com.sn68.agent.dataagent.dto.tool.ToolReferenceResp;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.dataagent.entity.AgentMcpTool;
import java.util.List;

/**
 * 执行资源服务契约。
 */
public interface ToolDirectoryService {

	/**
	 * 查询执行资源。
	 */
	List<ToolResourceDTO> listResources();

	/**
	 * 处理执行资源。
	 */
	IPage<ToolResourceDTO> pageResources(ToolPageQueryReq request);

	/**
	 * 保存执行资源。
	 */
	ToolResourceDTO saveResource(ToolResourceDTO request);

	/**
	 * 删除执行资源。
	 */
	void deleteResource(String resourceKey);

	/**
	 * 创建执行资源。
	 */
	ToolResourceDTO addMcpTool(String serverCode, String toolName);

	/**
	 * 查询执行资源。
	 */
	List<ToolReferenceResp> listReferences(String resourceKey);

	/**
	 * 查询执行资源。
	 */
	List<AgentMcpServer> listMcpServers();

	/**
	 * 查询执行资源。
	 */
	List<AgentMcpTool> listMcpTools(String serverCode);

	/**
	 * 处理执行资源。
	 */
	void syncMcpTools();

}
