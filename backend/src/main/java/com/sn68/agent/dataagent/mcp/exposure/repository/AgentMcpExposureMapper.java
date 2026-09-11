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
package com.sn68.agent.dataagent.mcp.exposure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposurePageQueryRequest;
import com.sn68.agent.dataagent.mcp.exposure.entity.AgentMcpExposure;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * AgentMCP暴露Mapper服务契约。
 */
@Repository
public interface AgentMcpExposureMapper extends SuperMapper<AgentMcpExposure> {

	/**
	 * 查询AgentMcpExposure数据访问相关数据。
	 */
	default List<AgentMcpExposure> findAllOrdered(String toolKey, String status) {
		LambdaQueryWrapper<AgentMcpExposure> wrapper = new LambdaQueryWrapper<AgentMcpExposure>()
			.eq(AgentMcpExposure::getDeleted, false)
			.orderByAsc(AgentMcpExposure::getDisplayOrder)
			.orderByDesc(AgentMcpExposure::getId);
		if (StringUtils.hasText(toolKey)) {
			wrapper.eq(AgentMcpExposure::getToolKey, toolKey.trim());
		}
		if (StringUtils.hasText(status)) {
			wrapper.eq(AgentMcpExposure::getStatus, status.trim());
		}
		return selectList(wrapper);
	}

	/**
	 * 查询AgentMcpExposure数据访问相关数据。
	 */
	default IPage<AgentMcpExposure> selectExposurePage(IPage<AgentMcpExposure> page,
			McpExposurePageQueryRequest request) {
		McpExposurePageQueryRequest query = request == null ? new McpExposurePageQueryRequest() : request;
		var wrapper = Wraps.<AgentMcpExposure>lbQ().eq(AgentMcpExposure::getDeleted, false)
			.eq(AgentMcpExposure::getToolKey, trim(query.getToolKey()))
			.eq(AgentMcpExposure::getStatus, trim(query.getStatus()));
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(item -> item.like(AgentMcpExposure::getExposureCode, keyword)
				.or()
				.like(AgentMcpExposure::getExposureName, keyword)
				.or()
				.like(AgentMcpExposure::getToolKey, keyword)
				.or()
				.like(AgentMcpExposure::getExposedToolName, keyword)
				.or()
				.like(AgentMcpExposure::getExposureType, keyword)
				.or()
				.like(AgentMcpExposure::getRiskLevel, keyword)
				.or()
				.like(AgentMcpExposure::getExtConfig, keyword));
		}
		return selectPage(page, wrapper.orderByAsc(AgentMcpExposure::getDisplayOrder)
			.orderByDesc(AgentMcpExposure::getId));
	}

	/**
	 * 查询AgentMcpExposure数据访问相关数据。
	 */
	default AgentMcpExposure findByExposureCode(String exposureCode) {
		if (!StringUtils.hasText(exposureCode)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentMcpExposure>()
			.eq(AgentMcpExposure::getDeleted, false)
			.eq(AgentMcpExposure::getExposureCode, exposureCode.trim()));
	}

	/**
	 * 按编码查询包含逻辑删除的数据。
	 *
	 * <p>刻意保留原生 SQL：改用 MyBatis-Plus API 会被 {@code @TableLogic} 自动追加
	 * {@code deleted = false}，恰好过滤掉本方法唯一要查的软删记录，「按编码判重后恢复」链路会静默失效。
	 */
	@Select("SELECT * FROM agent_mcp_exposure WHERE exposure_code = #{exposureCode} LIMIT 1")
	AgentMcpExposure findByExposureCodeIncludingDeleted(@Param("exposureCode") String exposureCode);

	/**
	 * 恢复逻辑删除的 MCP 暴露配置。
	 *
	 * <p>刻意保留原生 SQL：{@code @TableLogic} 下 MyBatis-Plus 会给 UPDATE 追加 {@code deleted = false}，
	 * 反删除只能走原生 SQL。
	 */
	@Update("UPDATE agent_mcp_exposure SET deleted = FALSE, last_modify_time = CURRENT_TIMESTAMP WHERE id = #{id}")
	int restoreById(@Param("id") Long id);

	/**
	 * 查询已启用且占用指定对外 Tool Name 的暴露配置。
	 */
	default AgentMcpExposure findEnabledByExposedToolName(String exposedToolName) {
		if (!StringUtils.hasText(exposedToolName)) {
			return null;
		}
		return selectOne(Wraps.<AgentMcpExposure>lbQ()
			.eq(AgentMcpExposure::getDeleted, false)
			.eq(AgentMcpExposure::getStatus, "enabled")
			.eq(AgentMcpExposure::getExposedToolName, exposedToolName.trim()));
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
