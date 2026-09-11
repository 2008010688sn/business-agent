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

import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationPolicyVersion;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent 授权策略版本 Mapper（PAP，PR-3b）。
 *
 * <p>发布不可变模型的核心：已发布版本（published=true）的 JSON 与 hash 只允许 INSERT 产生新行，
 * 任何 UPDATE 均带 published = false 守卫（SQL 层兜底，绕过应用层也改不动）。</p>
 *
 * <p>说明：发布翻转与草稿覆盖使用原生 SQL（@Update 注解，模块既有先例 SkillKnowledgeMapper）——
 * 两者都是"条件 UPDATE + 影响行数即 CAS 结果"语义，且条件列（published）与自增列无关，
 * Lambda 条件构造器无法表达"仅当当前值为 false 才更新"的守卫条件。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Repository
public interface AgentAuthorizationPolicyVersionMapper extends SuperMapper<AgentAuthorizationPolicyVersion> {

	/**
	 * 按主键 + 租户查询版本行；id 为空返回 null。
	 */
	default AgentAuthorizationPolicyVersion findByIdAndTenant(Long id, String tenantId) {
		if (id == null) {
			return null;
		}
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationPolicyVersion::getId, id)
			.last("limit 1"));
	}

	/**
	 * 查询策略的全部版本（含草稿与已发布），按版本号倒序。
	 */
	default List<AgentAuthorizationPolicyVersion> listByPolicyId(Long policyId, String tenantId) {
		return selectList(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationPolicyVersion::getPolicyId, policyId)
			.orderByDesc(AgentAuthorizationPolicyVersion::getVersionNo));
	}

	/**
	 * 查询策略最新版本行（最高版本号，可能是草稿也可能是已发布）；无版本返回 null。
	 */
	default AgentAuthorizationPolicyVersion findLatestByPolicyId(Long policyId, String tenantId) {
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationPolicyVersion::getPolicyId, policyId)
			.orderByDesc(AgentAuthorizationPolicyVersion::getVersionNo)
			.last("limit 1"));
	}

	/**
	 * 发布版本（草稿 → 已发布）：published=false 守卫的 CAS 更新。
	 *
	 * @return 影响行数：1-发布成功；0-版本不存在或已发布（幂等拒绝）
	 */
	@Update("UPDATE agent_authorization_policy_version SET published = true, last_modify_time = now() "
			+ "WHERE id = #{id} AND tenant_id = #{tenantId} AND published = false AND deleted = false")
	int casPublish(@Param("id") Long id, @Param("tenantId") String tenantId);

	/**
	 * 覆盖草稿版本 JSON 与 hash：published=false 守卫，已发布版本不可修改（SQL 层兜底不可变契约）。
	 *
	 * @return 影响行数：1-更新成功；0-版本不存在或已发布（拒绝修改）
	 */
	@Update("UPDATE agent_authorization_policy_version SET policy_json = "
			+ "#{policyJson,typeHandler=com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler}, "
			+ "policy_hash = #{policyHash}, last_modify_time = now() "
			+ "WHERE id = #{id} AND tenant_id = #{tenantId} AND published = false AND deleted = false")
	int updateDraft(@Param("id") Long id, @Param("tenantId") String tenantId, @Param("policyJson") String policyJson,
			@Param("policyHash") String policyHash);

	/**
	 * 按策略 ID 逻辑删除其全部版本行（策略删除时的级联清理）。
	 */
	default int deleteByPolicyId(Long policyId, String tenantId) {
		return delete(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentAuthorizationPolicyVersion::getPolicyId, policyId));
	}

	private static LbqWrapper<AgentAuthorizationPolicyVersion> tenantScoped(
			LbqWrapper<AgentAuthorizationPolicyVersion> wrapper, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问授权策略版本");
		}
		return wrapper.eq(AgentAuthorizationPolicyVersion::getTenantId, tenantId.trim());
	}

}
