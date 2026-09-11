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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.tool.ToolPageQueryReq;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.notification.service.NotificationAuthorizationService;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent能力资源Mapper服务契约。
 */
@Repository
public interface AgentExecutionResourceMapper extends SuperMapper<AgentExecutionResource> {

	/**
	 * 查询全部执行资源，按展示顺序、资源标识升序；逻辑删除自动过滤。
	 */
	default List<AgentExecutionResource> findAllOrdered() {
		return selectList(Wraps.<AgentExecutionResource>lbQ()
			.orderByAsc(AgentExecutionResource::getDisplayOrder)
			.orderByAsc(AgentExecutionResource::getResourceKey));
	}

	default List<AgentExecutionResource> findAllOrdered(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(Wraps.<AgentExecutionResource>lbQ()
			.eq(AgentExecutionResource::getTenantId, tenantId.trim())
			.orderByAsc(AgentExecutionResource::getDisplayOrder)
			.orderByAsc(AgentExecutionResource::getResourceKey));
	}

	/**
	 * 分页查询执行资源：资源类型为 CONNECTOR_ACTION 时改按通知资源 key/扩展配置命中，
	 * 其余类型精确匹配；关键字对 key/名称/服务/地址/工具/鉴权等宽泛模糊匹配。
	 */
	default IPage<AgentExecutionResource> selectResourcePage(IPage<AgentExecutionResource> page,
			ToolPageQueryReq request) {
		return selectResourcePage(page, request, null);
	}

	default IPage<AgentExecutionResource> selectResourcePage(IPage<AgentExecutionResource> page,
			ToolPageQueryReq request, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return page;
		}
		ToolPageQueryReq query = request == null ? new ToolPageQueryReq() : request;
		var wrapper = Wraps.<AgentExecutionResource>lbQ().eq(AgentExecutionResource::getTenantId, tenantId.trim());
		if (isConnectorAction(query.getResourceType())) {
			wrapper.and(item -> item.eq(AgentExecutionResource::getResourceKey,
				NotificationAuthorizationService.RESOURCE_KEY).or()
				.like(AgentExecutionResource::getExtConfig, "CONNECTOR_ACTION").or()
				.like(AgentExecutionResource::getExtConfig, "CONNECTOR"));
		}
		else if (StringUtils.hasText(query.getResourceType())) {
			wrapper.eq(AgentExecutionResource::getResourceType, query.getResourceType().trim());
		}
		wrapper.eq(AgentExecutionResource::getStatus, trim(query.getStatus()));
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(item -> item.like(AgentExecutionResource::getResourceKey, keyword)
				.or()
				.like(AgentExecutionResource::getResourceName, keyword)
				.or()
				.like(AgentExecutionResource::getServerCode, keyword)
				.or()
				.like(AgentExecutionResource::getServiceName, keyword)
				.or()
				.like(AgentExecutionResource::getBaseUrl, keyword)
				.or()
				.like(AgentExecutionResource::getToolName, keyword)
				.or()
				.like(AgentExecutionResource::getEndpointUrl, keyword)
				.or()
				.like(AgentExecutionResource::getHttpMethod, keyword)
				.or()
				.like(AgentExecutionResource::getAuthType, keyword)
				.or()
				.like(AgentExecutionResource::getCredentialRef, keyword)
				.or()
				.like(AgentExecutionResource::getExtConfig, keyword));
		}
		return selectPage(page, wrapper.orderByAsc(AgentExecutionResource::getDisplayOrder)
			.orderByAsc(AgentExecutionResource::getResourceKey));
	}

	/**
	 * 查询已启用且未禁用（enabled=true 且 status!=disabled）的执行资源，按展示顺序、资源标识升序。
	 */
	default List<AgentExecutionResource> findEnabled() {
		return selectList(Wraps.<AgentExecutionResource>lbQ()
			.eq(AgentExecutionResource::getEnabled, true)
			.ne(AgentExecutionResource::getStatus, "disabled")
			.orderByAsc(AgentExecutionResource::getDisplayOrder)
			.orderByAsc(AgentExecutionResource::getResourceKey));
	}

	/**
	 * 按资源标识查询未删除的执行资源（不校验启用状态）；resourceKey 为空返回 null。
	 */
	default AgentExecutionResource findByResourceKey(String resourceKey) {
		if (!StringUtils.hasText(resourceKey)) {
			return null;
		}
		return selectOne(Wraps.<AgentExecutionResource>lbQ()
			.eq(AgentExecutionResource::getResourceKey, resourceKey.trim()));
	}

	/**
	 * 按资源标识查询包含逻辑删除的数据。
	 *
	 * <p>刻意保留原生 SQL：改用 MyBatis-Plus API 会被 {@code @TableLogic} 自动追加
	 * {@code deleted = false}，恰好过滤掉本方法唯一要查的软删记录，「按编码判重后恢复」链路会静默失效。
	 */
	@Select("SELECT * FROM agent_execution_resource WHERE resource_key = #{resourceKey} LIMIT 1")
	AgentExecutionResource findByResourceKeyIncludingDeleted(@Param("resourceKey") String resourceKey);

	/**
	 * 恢复逻辑删除的执行资源。
	 *
	 * <p>刻意保留原生 SQL：{@code @TableLogic} 下 MyBatis-Plus 会给 UPDATE 追加 {@code deleted = false}，
	 * 反删除只能走原生 SQL。
	 */
	@Update("UPDATE agent_execution_resource SET deleted = FALSE, last_modify_time = CURRENT_TIMESTAMP WHERE id = #{id}")
	int restoreById(@Param("id") Long id);

	/**
	 * 按资源标识查询已启用且未禁用的执行资源；resourceKey 为空返回 null。
	 */
	default AgentExecutionResource findEnabledByResourceKey(String resourceKey) {
		if (!StringUtils.hasText(resourceKey)) {
			return null;
		}
		return selectOne(Wraps.<AgentExecutionResource>lbQ()
			.eq(AgentExecutionResource::getResourceKey, resourceKey.trim())
			.eq(AgentExecutionResource::getEnabled, true)
			.ne(AgentExecutionResource::getStatus, "disabled"));
	}

	/**
	 * 资源类型是否为连接器动作（CONNECTOR_ACTION，大小写不敏感）。
	 */
	private static boolean isConnectorAction(String resourceType) {
		return StringUtils.hasText(resourceType) && "CONNECTOR_ACTION".equalsIgnoreCase(resourceType.trim());
	}

	/**
	 * 空白串归一化为 null，配合 Wraps 自动跳过空条件。
	 */
	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
