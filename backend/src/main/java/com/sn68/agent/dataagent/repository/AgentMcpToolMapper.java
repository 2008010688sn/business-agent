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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sn68.agent.dataagent.entity.AgentMcpTool;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/**
 * AgentMCP工具Mapper服务契约。
 */
@Repository
public interface AgentMcpToolMapper extends SuperMapper<AgentMcpTool> {

	/**
	 * 查询 MCP 服务下全部工具，按工具名升序；逻辑删除自动过滤。
	 */
	default List<AgentMcpTool> findByServerCode(String serverCode) {
		return selectList(new LambdaQueryWrapper<AgentMcpTool>().eq(AgentMcpTool::getServerCode, serverCode)
			.orderByAsc(AgentMcpTool::getToolName));
	}

	/**
	 * 按服务编码 + 工具名精确查询未删除的 MCP 工具。
	 */
	default AgentMcpTool findByServerCodeAndToolName(String serverCode, String toolName) {
		return selectOne(new LambdaQueryWrapper<AgentMcpTool>().eq(AgentMcpTool::getServerCode, serverCode)
			.eq(AgentMcpTool::getToolName, toolName));
	}

	/**
	 * 按服务与工具名查询包含逻辑删除的数据。
	 *
	 * <p>刻意保留原生 SQL：改用 MyBatis-Plus API 会被 {@code @TableLogic} 自动追加
	 * {@code deleted = false}，恰好过滤掉本方法唯一要查的软删记录，「按编码判重后恢复」链路会静默失效。
	 */
	@Select("SELECT * FROM agent_mcp_tool WHERE server_code = #{serverCode} AND tool_name = #{toolName} LIMIT 1")
	AgentMcpTool findByServerCodeAndToolNameIncludingDeleted(@Param("serverCode") String serverCode,
			@Param("toolName") String toolName);

	/**
	 * 恢复逻辑删除的 MCP 工具。
	 *
	 * <p>刻意保留原生 SQL：{@code @TableLogic} 下 MyBatis-Plus 会给 UPDATE 追加 {@code deleted = false}，
	 * 反删除只能走原生 SQL。
	 */
	@Update("UPDATE agent_mcp_tool SET deleted = FALSE, last_modify_time = CURRENT_TIMESTAMP WHERE id = #{id}")
	int restoreById(@Param("id") Long id);

	/**
	 * 同步 MCP 工具清单：不在 toolNames 中的工具先置 disabled 再逻辑删除；
	 * toolNames 为空时表示服务端已无工具，服务下全部工具都会被清理。
	 */
	default int deleteToolsNotIn(String serverCode, List<String> toolNames) {
		LambdaUpdateWrapper<AgentMcpTool> wrapper = Wraps.<AgentMcpTool>lbU()
			.eq(AgentMcpTool::getServerCode, serverCode)
			.set(AgentMcpTool::getStatus, "disabled");
		if (toolNames != null && !toolNames.isEmpty()) {
			wrapper.notIn(AgentMcpTool::getToolName, toolNames);
		}
		update(null, wrapper);
		LambdaQueryWrapper<AgentMcpTool> deleteWrapper = new LambdaQueryWrapper<AgentMcpTool>()
			.eq(AgentMcpTool::getServerCode, serverCode);
		if (toolNames != null && !toolNames.isEmpty()) {
			deleteWrapper.notIn(AgentMcpTool::getToolName, toolNames);
		}
		return delete(deleteWrapper);
	}

}
