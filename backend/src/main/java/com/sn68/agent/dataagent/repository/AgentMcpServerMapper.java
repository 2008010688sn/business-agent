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
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * AgentMCPServerMapper服务契约。
 */
@Repository
public interface AgentMcpServerMapper extends SuperMapper<AgentMcpServer> {

	/**
	 * 查询全部未删除的 MCP 服务，按服务编码升序（逻辑删除由 {@code @TableLogic} 过滤，即"active"语义）。
	 */
	default List<AgentMcpServer> findAllActive() {
		return selectList(Wraps.<AgentMcpServer>lbQ().orderByAsc(AgentMcpServer::getServerCode));
	}

	/**
	 * 按服务编码精确查询 MCP 服务。
	 */
	default AgentMcpServer findByServerCode(String serverCode) {
		return selectOne(new LambdaQueryWrapper<AgentMcpServer>().eq(AgentMcpServer::getServerCode, serverCode));
	}

}
