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

import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationBinding;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent 授权策略绑定 Mapper（PAP，PR-3b）。
 *
 * <p>bind_revision 乐观锁：CAS 更新以影响行数判定成功；并发冲突方拿到 0 行后重查当前值重试。
 * 原生 SQL 的原因：自增表达式 bind_revision = bind_revision + 1 引用自身列，Lambda 条件构造器无法表达。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Repository
public interface AgentAuthorizationBindingMapper extends SuperMapper<AgentAuthorizationBinding> {

	/**
	 * 查询 owner 在指定环境下的唯一绑定（部分唯一索引 owner_type + owner_id + environment 兜底）；参数缺失返回 null。
	 */
	default AgentAuthorizationBinding findBinding(AuthorizationOwnerType ownerType, Long ownerId,
			AuthorizationEnvironment environment, String tenantId) {
		if (ownerType == null || ownerId == null || environment == null) {
			return null;
		}
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationBinding::getOwnerType, ownerType)
			.eq(AgentAuthorizationBinding::getOwnerId, ownerId)
			.eq(AgentAuthorizationBinding::getEnvironment, environment)
			.last("limit 1"));
	}

	/**
	 * 按主键 + 租户查询绑定行；id 为空返回 null。
	 */
	default AgentAuthorizationBinding findByIdAndTenant(Long id, String tenantId) {
		if (id == null) {
			return null;
		}
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationBinding::getId, id)
			.last("limit 1"));
	}

	/**
	 * CAS 更新绑定的策略版本指针：仅当 bind_revision 与调用方读到的快照一致时生效并自增。
	 *
	 * @return 影响行数：1-更新成功；0-版本号过期（并发冲突）
	 */
	@Update("UPDATE agent_authorization_binding SET policy_version_id = #{policyVersionId}, "
			+ "bind_revision = bind_revision + 1, last_modify_time = now() "
			+ "WHERE id = #{id} AND tenant_id = #{tenantId} AND bind_revision = #{expectedRevision} "
			+ "AND deleted = false")
	int casUpdateBinding(@Param("id") Long id, @Param("tenantId") String tenantId,
			@Param("policyVersionId") Long policyVersionId, @Param("expectedRevision") Long expectedRevision);

	/**
	 * 统计引用指定策略版本的绑定数（策略删除前的引用检查）。
	 */
	default long countByPolicyVersionId(Long policyVersionId) {
		return selectCount(Wraps.<AgentAuthorizationBinding>lbQ()
			.eq(AgentAuthorizationBinding::getPolicyVersionId, policyVersionId));
	}

	private static LbqWrapper<AgentAuthorizationBinding> tenantScoped(LbqWrapper<AgentAuthorizationBinding> wrapper,
			String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问授权绑定");
		}
		return wrapper.eq(AgentAuthorizationBinding::getTenantId, tenantId.trim());
	}

}
