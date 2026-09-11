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
package com.sn68.agent.dataagent.authorization.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyPageQueryReq;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationPolicy;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent 授权策略主档 Mapper（PAP，PR-3b）。
 *
 * <p>租户插件白名单默认空（模块 AGENTS.md 高风险点），所有查询显式携带 tenant_id 谓词。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Repository
public interface AgentAuthorizationPolicyMapper extends SuperMapper<AgentAuthorizationPolicy> {

	/**
	 * 按主键 + 租户查询策略主档；id 为空返回 null。
	 */
	default AgentAuthorizationPolicy findByIdAndTenant(Long id, String tenantId) {
		if (id == null) {
			return null;
		}
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationPolicy::getId, id)
			.last("limit 1"));
	}

	/**
	 * 按策略编码 + 租户查询（租户内唯一索引兜底）；code 为空返回 null。
	 */
	default AgentAuthorizationPolicy findByCodeAndTenant(String code, String tenantId) {
		if (!StringUtils.hasText(code)) {
			return null;
		}
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationPolicy::getCode, code)
			.last("limit 1"));
	}

	/**
	 * 分页查询当前租户的策略主档：编码/名称模糊、状态/模板精确，按创建时间倒序。
	 */
	default IPage<AgentAuthorizationPolicy> selectPolicyPage(IPage<AgentAuthorizationPolicy> page,
			AuthorizationPolicyPageQueryReq query, String tenantId) {
		AuthorizationPolicyPageQueryReq request = query == null ? new AuthorizationPolicyPageQueryReq() : query;
		LbqWrapper<AgentAuthorizationPolicy> wrapper = tenantScoped(Wraps.lbQ(), tenantId);
		if (StringUtils.hasText(request.getCode())) {
			wrapper.like(AgentAuthorizationPolicy::getCode, request.getCode().trim());
		}
		if (StringUtils.hasText(request.getName())) {
			wrapper.like(AgentAuthorizationPolicy::getName, request.getName().trim());
		}
		if (StringUtils.hasText(request.getStatus())) {
			wrapper.eq(AgentAuthorizationPolicy::getStatus, request.getStatus().trim().toUpperCase());
		}
		if (StringUtils.hasText(request.getTemplateCode())) {
			wrapper.eq(AgentAuthorizationPolicy::getTemplateCode, request.getTemplateCode().trim().toUpperCase());
		}
		return selectPage(page, wrapper.orderByDesc(AgentAuthorizationPolicy::getCreateTime));
	}

	private static LbqWrapper<AgentAuthorizationPolicy> tenantScoped(LbqWrapper<AgentAuthorizationPolicy> wrapper,
			String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问授权策略");
		}
		return wrapper.eq(AgentAuthorizationPolicy::getTenantId, tenantId.trim());
	}

}
