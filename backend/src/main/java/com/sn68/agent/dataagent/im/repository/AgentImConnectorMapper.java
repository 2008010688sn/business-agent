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

import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * AgentIM连接器Mapper服务契约。
 *
 * <p>连接器唯一键为 (tenant_id, provider, connector_code)。管理端查询必须带租户条件；
 * 仅回调定位与 Stream 生命周期两类系统级路径允许跨租户查询，方法名以 AllTenants/Candidates 显式标识。
 */
@Repository
public interface AgentImConnectorMapper extends SuperMapper<AgentImConnector> {

	/**
	 * 查询当前租户全部连接器（管理端）。租户ID为空时直接返回空集合，禁止退化为全局查询。
	 */
	default List<AgentImConnector> findAllOrdered(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(Wraps.<AgentImConnector>lbQ()
			.eq(AgentImConnector::getTenantId, tenantId.trim())
			.orderByAsc(AgentImConnector::getDisplayOrder)
			.orderByAsc(AgentImConnector::getConnectorCode));
	}

	/**
	 * 系统级：查询所有租户的连接器。仅供 Stream 生命周期启动/刷新使用，业务查询禁止调用。
	 */
	default List<AgentImConnector> findAllOrderedAllTenants() {
		return selectList(Wraps.<AgentImConnector>lbQ()
			.orderByAsc(AgentImConnector::getDisplayOrder)
			.orderByAsc(AgentImConnector::getConnectorCode));
	}

	/**
	 * 按租户 + 连接器编码查询（管理端）。
	 */
	default AgentImConnector findByTenantAndConnectorCode(String tenantId, String connectorCode) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(connectorCode)) {
			return null;
		}
		return selectOne(Wraps.<AgentImConnector>lbQ()
			.eq(AgentImConnector::getTenantId, tenantId.trim())
			.eq(AgentImConnector::getConnectorCode, connectorCode.trim())
			.last(" limit 1"));
	}

	/**
	 * 按租户 + 平台 + 连接器编码查询（管理端唯一性校验）。
	 */
	default AgentImConnector findByTenantProviderAndCode(String tenantId, String provider, String connectorCode) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(provider) || !StringUtils.hasText(connectorCode)) {
			return null;
		}
		return selectOne(Wraps.<AgentImConnector>lbQ()
			.eq(AgentImConnector::getTenantId, tenantId.trim())
			.eq(AgentImConnector::getProvider, provider.trim().toUpperCase())
			.eq(AgentImConnector::getConnectorCode, connectorCode.trim())
			.last(" limit 1"));
	}

	/**
	 * 按租户 + 连接器编码查询启用连接器（管理端）。
	 */
	default AgentImConnector findEnabledByTenantAndConnectorCode(String tenantId, String connectorCode) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(connectorCode)) {
			return null;
		}
		return selectOne(Wraps.<AgentImConnector>lbQ()
			.eq(AgentImConnector::getTenantId, tenantId.trim())
			.eq(AgentImConnector::getConnectorCode, connectorCode.trim())
			.eq(AgentImConnector::getStatus, ImConstants.STATUS_ENABLED)
			.last(" limit 1"));
	}

	/**
	 * 系统级：按回调路径 (provider, connectorCode) 查询候选连接器。
	 *
	 * <p>回调入站时尚未验签，无法信任任何租户参数，因此这里允许跨租户返回多个候选；
	 * 调用方必须逐候选用各自密钥验签，验签通过的候选才能确定归属租户（见 ImCallbackService）。
	 */
	default List<AgentImConnector> findCallbackCandidates(String provider, String connectorCode) {
		if (!StringUtils.hasText(provider) || !StringUtils.hasText(connectorCode)) {
			return List.of();
		}
		return selectList(Wraps.<AgentImConnector>lbQ()
			.eq(AgentImConnector::getProvider, provider.trim().toUpperCase())
			.eq(AgentImConnector::getConnectorCode, connectorCode.trim())
			.orderByAsc(AgentImConnector::getId));
	}

}
