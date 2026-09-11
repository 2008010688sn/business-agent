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
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityAgentOptionPageQueryReq;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

/**
 * DataAgentMapper服务契约。
 */
@Repository
public interface DataAgentMapper extends SuperMapper<DataAgent> {

	/**
	 * 查询全部 Agent，按创建时间倒序；逻辑删除自动过滤。
	 *
	 * <p>无租户时返回空列表（失败关闭）。管理端必须传入当前登录租户：平台管理员会被
	 * 框架租户拦截器豁免，不传 tenantId 仍会看到全部租户。
	 */
	default List<DataAgent> findAll() {
		return findAll(null);
	}

	default List<DataAgent> findAll(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(Wraps.<DataAgent>lbQ()
			.eq(DataAgent::getTenantId, tenantId.trim())
			.orderByDesc(DataAgent::getCreateTime));
	}

	/**
	 * 按主键查询 Agent（selectById 的语义别名）。
	 */
	default DataAgent findById(Long id) {
		return selectById(id);
	}

	/**
	 * 按状态查询 Agent，按创建时间倒序；status 为必填条件，调用方保证非空。
	 *
	 * <p>无租户重载仅保留给启动期 {@code findByStatusRaw}；管理查询走
	 * {@link #findByStatus(String, String)}。
	 */
	default List<DataAgent> findByStatus(String status) {
		return selectList(new LambdaQueryWrapper<DataAgent>().eq(DataAgent::getStatus, status).orderByDesc(DataAgent::getCreateTime));
	}

	default List<DataAgent> findByStatus(String status, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<DataAgent>()
			.eq(DataAgent::getTenantId, tenantId.trim())
			.eq(DataAgent::getStatus, status)
			.orderByDesc(DataAgent::getCreateTime));
	}

	/**
	 * 按关键字模糊搜索 Agent（名称/描述/标签任一命中），按创建时间倒序。
	 */
	default List<DataAgent> searchByKeyword(String keyword) {
		return searchByKeyword(keyword, null);
	}

	default List<DataAgent> searchByKeyword(String keyword, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<DataAgent>()
			.eq(DataAgent::getTenantId, tenantId.trim())
			.and(wrapper -> wrapper.like(DataAgent::getName, keyword)
				.or()
				.like(DataAgent::getDescription, keyword)
				.or()
				.like(DataAgent::getTags, keyword))
			.orderByDesc(DataAgent::getCreateTime));
	}

	/**
	 * 组合条件查询 Agent：状态精确匹配 + 关键字（名称/描述/标签）模糊匹配，条件为空时自动忽略。
	 */
	default List<DataAgent> findByConditions(String status, String keyword) {
		return findByConditions(status, keyword, null);
	}

	default List<DataAgent> findByConditions(String status, String keyword, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		LbqWrapper<DataAgent> wrapper = Wraps.<DataAgent>lbQ()
			.eq(DataAgent::getTenantId, tenantId.trim())
			.orderByDesc(DataAgent::getCreateTime);
		if (status != null && !status.isBlank()) {
			wrapper.eq(DataAgent::getStatus, status);
		}
		if (keyword != null && !keyword.isBlank()) {
			wrapper.and(item -> item.like(DataAgent::getName, keyword)
				.or()
				.like(DataAgent::getDescription, keyword)
				.or()
				.like(DataAgent::getTags, keyword));
		}
		return selectList(wrapper);
	}

	/**
	 * 可见性配置页的 Agent 候选分页：支持按 Agent ID、状态过滤，关键字同时匹配名称/描述/标签，
	 * 关键字为纯数字时额外按 ID 精确匹配。
	 */
	default IPage<DataAgent> selectVisibilityAgentOptionPage(IPage<DataAgent> page,
			AgentVisibilityAgentOptionPageQueryReq request) {
		return selectVisibilityAgentOptionPage(page, request, null);
	}

	default IPage<DataAgent> selectVisibilityAgentOptionPage(IPage<DataAgent> page,
			AgentVisibilityAgentOptionPageQueryReq request, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return page;
		}
		AgentVisibilityAgentOptionPageQueryReq query = request == null
				? new AgentVisibilityAgentOptionPageQueryReq() : request;
		LbqWrapper<DataAgent> wrapper = Wraps.<DataAgent>lbQ()
			.eq(DataAgent::getTenantId, tenantId.trim())
			.eq(DataAgent::getId, query.getAgentId())
			.eq(DataAgent::getStatus, trim(query.getStatus()));
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			Long agentId = parseLong(keyword);
			wrapper.and(item -> {
				item.like(DataAgent::getName, keyword)
					.or()
					.like(DataAgent::getDescription, keyword)
					.or()
					.like(DataAgent::getTags, keyword);
				if (agentId != null) {
					item.or().eq(DataAgent::getId, agentId);
				}
			});
		}
		return selectPage(page, wrapper.orderByDesc(DataAgent::getCreateTime));
	}

	/**
	 * 重置 Agent 的 API Key 并同步启用开关（显式 set，支持置 null）。
	 */
	default int updateApiKey(Long id, String apiKey, Boolean apiKeyEnabled) {
		return update(null, Wraps.<DataAgent>lbU().eq(DataAgent::getId, id)
			.set(DataAgent::getApiKey, apiKey)
			.set(DataAgent::getApiKeyEnabled, apiKeyEnabled)
			.set(DataAgent::getLastModifyTime, Instant.now()));
	}

	/**
	 * 仅切换 Agent API Key 的启用状态，不改动 Key 本身。
	 */
	default int toggleApiKey(Long id, Boolean enabled) {
		return update(null, Wraps.<DataAgent>lbU().eq(DataAgent::getId, id)
			.set(DataAgent::getApiKeyEnabled, enabled)
			.set(DataAgent::getLastModifyTime, Instant.now()));
	}

	/**
	 * 空白串归一化为 null，配合 Wraps 自动跳过空条件。
	 */
	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	/**
	 * 关键字尝试解析为 Long（用于按 ID 搜索），非数字返回 null。
	 */
	private static Long parseLong(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
