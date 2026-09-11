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
package com.sn68.agent.dataagent.im.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.im.entity.AgentImUserIdentity;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * AgentIMUserIdentityMapper服务契约。
 */
@Repository
public interface AgentImUserIdentityMapper extends SuperMapper<AgentImUserIdentity> {

	/**
	 * 查询指定租户全部身份映射（管理端）。租户ID为空直接返回空集合，禁止退化为全局查询。
	 */
	default List<AgentImUserIdentity> findAllOrdered(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<AgentImUserIdentity>()
			.eq(AgentImUserIdentity::getTenantId, tenantId.trim())
			.orderByDesc(AgentImUserIdentity::getLastModifyTime)
			.orderByDesc(AgentImUserIdentity::getCreateTime));
	}

	/**
	 * 查询指定租户下启用的外部用户身份映射（回调链路使用，租户以验签通过的连接器为准）。
	 */
	default AgentImUserIdentity findEnabledByExternalUser(String tenantId, String provider, String connectorCode,
			String externalUserId) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(provider) || !StringUtils.hasText(externalUserId)) {
			return null;
		}
		LambdaQueryWrapper<AgentImUserIdentity> wrapper = new LambdaQueryWrapper<AgentImUserIdentity>()
			.eq(AgentImUserIdentity::getTenantId, tenantId.trim())
			.eq(AgentImUserIdentity::getProvider, provider.trim().toUpperCase())
			.eq(AgentImUserIdentity::getExternalUserId, externalUserId.trim())
			.eq(AgentImUserIdentity::getBindStatus, ImConstants.STATUS_ENABLED);
		if (StringUtils.hasText(connectorCode)) {
			wrapper.and(nested -> nested.eq(AgentImUserIdentity::getConnectorCode, connectorCode.trim())
				.or()
				.isNull(AgentImUserIdentity::getConnectorCode)
				.or()
				.eq(AgentImUserIdentity::getConnectorCode, ""));
		}
		return selectOne(wrapper.orderByDesc(AgentImUserIdentity::getConnectorCode).last(" limit 1"));
	}

	/**
	 * 查询指定租户下的外部用户身份映射（含未启用，回调自动绑定使用）。
	 */
	default AgentImUserIdentity findByExternalUser(String tenantId, String provider, String connectorCode,
			String externalUserId) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(provider) || !StringUtils.hasText(externalUserId)) {
			return null;
		}
		LambdaQueryWrapper<AgentImUserIdentity> wrapper = new LambdaQueryWrapper<AgentImUserIdentity>()
			.eq(AgentImUserIdentity::getTenantId, tenantId.trim())
			.eq(AgentImUserIdentity::getProvider, provider.trim().toUpperCase())
			.eq(AgentImUserIdentity::getExternalUserId, externalUserId.trim());
		if (StringUtils.hasText(connectorCode)) {
			wrapper.eq(AgentImUserIdentity::getConnectorCode, connectorCode.trim());
		}
		return selectOne(wrapper.last(" limit 1"));
	}

}
