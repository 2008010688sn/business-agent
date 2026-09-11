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

import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationGrant;
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantPermission;
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantSubjectType;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent 授权记录 Mapper（PAP，PR-3b）。
 *
 * <p>租户插件白名单默认空，所有查询显式携带 tenant_id 谓词。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Repository
public interface AgentAuthorizationGrantMapper extends SuperMapper<AgentAuthorizationGrant> {

	/**
	 * 授权状态常量（与 DDL CHECK 约束一致）。
	 */
	String GRANT_STATUS_ACTIVE = "ACTIVE";

	String GRANT_STATUS_REVOKED = "REVOKED";

	/**
	 * 查询 owner 的全部授权记录（不分状态），按创建时间倒序；供管理端列表展示。
	 */
	default List<AgentAuthorizationGrant> listByOwner(AuthorizationOwnerType ownerType, Long ownerId,
			String tenantId) {
		return selectList(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationGrant::getOwnerType, ownerType)
			.eq(AgentAuthorizationGrant::getOwnerId, ownerId)
			.orderByDesc(AgentAuthorizationGrant::getCreateTime));
	}

	/**
	 * 查询 owner 当前生效的授权（ACTIVE 且未过期）；供运行时判定路径消费。
	 */
	default List<AgentAuthorizationGrant> listActiveByOwner(AuthorizationOwnerType ownerType, Long ownerId,
			String tenantId) {
		return selectList(activeWrapper(tenantId)
			.eq(AgentAuthorizationGrant::getOwnerType, ownerType)
			.eq(AgentAuthorizationGrant::getOwnerId, ownerId));
	}

	/**
	 * 查询主体在 owner 上当前生效的指定权限授权；参数缺失返回 null（幂等创建时定位记录）。
	 */
	default AgentAuthorizationGrant findActive(AuthorizationOwnerType ownerType, Long ownerId,
			AuthorizationGrantSubjectType subjectType, String subjectId, AuthorizationGrantPermission permission,
			String tenantId) {
		if (ownerType == null || ownerId == null || subjectType == null || permission == null
				|| subjectId == null || subjectId.isBlank()) {
			return null;
		}
		return selectOne(activeWrapper(tenantId)
			.eq(AgentAuthorizationGrant::getOwnerType, ownerType)
			.eq(AgentAuthorizationGrant::getOwnerId, ownerId)
			.eq(AgentAuthorizationGrant::getSubjectType, subjectType)
			.eq(AgentAuthorizationGrant::getSubjectId, subjectId.trim())
			.eq(AgentAuthorizationGrant::getPermission, permission)
			.last("limit 1"));
	}

	/**
	 * 按主键 + 租户查询授权记录；id 为空返回 null。
	 */
	default AgentAuthorizationGrant findByIdAndTenant(Long id, String tenantId) {
		if (id == null) {
			return null;
		}
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationGrant::getId, id)
			.last("limit 1"));
	}

	/**
	 * 生效授权公共条件：状态 ACTIVE 且（无过期时间或过期时间晚于当前时刻）+ 租户隔离。
	 */
	private static LbqWrapper<AgentAuthorizationGrant> activeWrapper(String tenantId) {
		Instant now = Instant.now();
		return tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationGrant::getStatus, GRANT_STATUS_ACTIVE)
			.and(wrapper -> wrapper.isNull(AgentAuthorizationGrant::getExpireTime)
				.or()
				.gt(AgentAuthorizationGrant::getExpireTime, now));
	}

	private static LbqWrapper<AgentAuthorizationGrant> tenantScoped(LbqWrapper<AgentAuthorizationGrant> wrapper,
			String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问授权记录");
		}
		return wrapper.eq(AgentAuthorizationGrant::getTenantId, tenantId.trim());
	}

}
