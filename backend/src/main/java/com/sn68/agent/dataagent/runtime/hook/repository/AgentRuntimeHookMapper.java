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
package com.sn68.agent.dataagent.runtime.hook.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookPageQueryRequest;
import com.sn68.agent.dataagent.runtime.hook.entity.AgentRuntimeHook;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent 运行时钩子 Mapper：钩子按事件类型触发，作用域维度（Agent/技能/版本/资源键）为空表示通配。
 */
@Repository
public interface AgentRuntimeHookMapper extends SuperMapper<AgentRuntimeHook> {

	/**
	 * 管理端列表查询：按事件类型/Agent/技能/版本/资源键可选过滤（为空则不限），
	 * 未删除，按展示顺序升序、ID 倒序。
	 */
	default List<AgentRuntimeHook> findAllOrdered(String tenantId, String eventType, Long agentId, String skillCode,
			Long skillVersionId, String resourceKey) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(Wraps.<AgentRuntimeHook>lbQ()
			.eq(AgentRuntimeHook::getTenantId, tenantId.trim())
			.eq(AgentRuntimeHook::getDeleted, false)
			.eq(AgentRuntimeHook::getEventType, trim(eventType))
			.eq(AgentRuntimeHook::getAgentId, agentId)
			.eq(AgentRuntimeHook::getSkillCode, trim(skillCode))
			.eq(AgentRuntimeHook::getSkillVersionId, skillVersionId)
			.eq(AgentRuntimeHook::getResourceKey, trim(resourceKey))
			.orderByAsc(AgentRuntimeHook::getDisplayOrder)
			.orderByDesc(AgentRuntimeHook::getId));
	}

	/**
	 * 管理端分页查询：按事件类型/Agent/技能/版本/资源键可选过滤，未删除，按展示顺序升序、ID 倒序。
	 */
	default IPage<AgentRuntimeHook> selectHookPage(IPage<AgentRuntimeHook> page, RuntimeHookPageQueryRequest request,
			String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return page;
		}
		RuntimeHookPageQueryRequest query = request == null ? new RuntimeHookPageQueryRequest() : request;
		return selectPage(page, Wraps.<AgentRuntimeHook>lbQ()
			.eq(AgentRuntimeHook::getTenantId, tenantId.trim())
			.eq(AgentRuntimeHook::getDeleted, false)
			.eq(AgentRuntimeHook::getEventType, trim(query.getEventType()))
			.eq(AgentRuntimeHook::getAgentId, query.getAgentId())
			.eq(AgentRuntimeHook::getSkillCode, trim(query.getSkillCode()))
			.eq(AgentRuntimeHook::getSkillVersionId, query.getSkillVersionId())
			.eq(AgentRuntimeHook::getResourceKey, trim(query.getResourceKey()))
			.orderByAsc(AgentRuntimeHook::getDisplayOrder)
			.orderByDesc(AgentRuntimeHook::getId));
	}

	/**
	 * 按钩子编码查询未删除的钩子（编码唯一性校验/详情定位用）；编码为空返回 null。
	 */
	default AgentRuntimeHook findByHookCode(String hookCode) {
		if (!StringUtils.hasText(hookCode)) {
			return null;
		}
		return selectOne(Wraps.<AgentRuntimeHook>lbQ()
			.eq(AgentRuntimeHook::getDeleted, false)
			.eq(AgentRuntimeHook::getHookCode, hookCode.trim()));
	}

	/**
	 * 运行期钩子匹配：查询指定事件类型下未禁用的钩子，各作用域维度（Agent/技能/版本/资源键）
	 * 命中"维度为空（通配）或等于当前值"的记录；事件类型为空返回空集合。
	 *
	 * <p>此处保留原生 LambdaQueryWrapper：作用域"未传值时必须命中 IS NULL"的语义与 Wraps
	 * 自动跳空（未传值即不加条件）相反，误用 Wraps 会把窄作用域钩子放大为全局钩子。
	 */
	default List<AgentRuntimeHook> findMatching(String tenantId, String eventType, Long agentId, String skillCode,
			Long skillVersionId, String resourceKey) {
		if (!StringUtils.hasText(eventType) || !StringUtils.hasText(tenantId)) {
			return List.of();
		}
		LambdaQueryWrapper<AgentRuntimeHook> wrapper = new LambdaQueryWrapper<AgentRuntimeHook>()
			.eq(AgentRuntimeHook::getTenantId, tenantId.trim())
			.eq(AgentRuntimeHook::getEventType, eventType.trim())
			.eq(AgentRuntimeHook::getDeleted, false)
			.ne(AgentRuntimeHook::getStatus, "disabled")
			.orderByAsc(AgentRuntimeHook::getDisplayOrder)
			.orderByAsc(AgentRuntimeHook::getHookCode);
		scopeMatch(wrapper, AgentRuntimeHook::getAgentId, agentId);
		scopeMatch(wrapper, AgentRuntimeHook::getSkillCode, skillCode);
		scopeMatch(wrapper, AgentRuntimeHook::getSkillVersionId, skillVersionId);
		scopeMatch(wrapper, AgentRuntimeHook::getResourceKey, resourceKey);
		return selectList(wrapper);
	}

	private static <T> void scopeMatch(LambdaQueryWrapper<AgentRuntimeHook> wrapper,
			com.baomidou.mybatisplus.core.toolkit.support.SFunction<AgentRuntimeHook, T> column, T value) {
		if (value instanceof String text) {
			if (StringUtils.hasText(text)) {
				wrapper.and(w -> w.isNull(column).or().eq(column, text.trim()));
			}
			else {
				wrapper.isNull(column);
			}
			return;
		}
		if (value != null) {
			wrapper.and(w -> w.isNull(column).or().eq(column, value));
		}
		else {
			wrapper.isNull(column);
		}
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
