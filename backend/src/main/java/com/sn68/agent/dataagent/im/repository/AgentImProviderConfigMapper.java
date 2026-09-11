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
import com.sn68.agent.dataagent.im.entity.AgentImProviderConfig;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * AgentIM平台配置Mapper服务契约。
 */
@Repository
public interface AgentImProviderConfigMapper extends SuperMapper<AgentImProviderConfig> {

	/**
	 * 查询指定租户的 IM 平台配置，按展示顺序与平台名排序。
	 */
	default List<AgentImProviderConfig> findAllOrdered(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<AgentImProviderConfig>()
			.eq(AgentImProviderConfig::getTenantId, tenantId.trim())
			.orderByAsc(AgentImProviderConfig::getDisplayOrder)
			.orderByAsc(AgentImProviderConfig::getProvider));
	}

	/**
	 * 按平台标识查询配置（大小写不敏感，入参会转大写）；平台为空返回 null。
	 */
	default AgentImProviderConfig findByProvider(String provider) {
		if (!StringUtils.hasText(provider)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentImProviderConfig>()
			.eq(AgentImProviderConfig::getProvider, provider.trim().toUpperCase())
			.last(" limit 1"));
	}

	/**
	 * 按平台标识查询启用状态的配置，用于回调/发送前校验平台可用；平台为空或未启用返回 null。
	 */
	default AgentImProviderConfig findEnabledByProvider(String provider) {
		if (!StringUtils.hasText(provider)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentImProviderConfig>()
			.eq(AgentImProviderConfig::getProvider, provider.trim().toUpperCase())
			.eq(AgentImProviderConfig::getStatus, ImConstants.STATUS_ENABLED)
			.last(" limit 1"));
	}

}
