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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sn68.agent.dataagent.entity.AgentMemory;
import com.sn68.agent.dataagent.enums.AgentMemoryStatus;
import com.sn68.agent.dataagent.enums.MemoryConsentStatus;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.enums.MemorySensitivity;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent记忆Mapper服务契约。
 *
 * <p>范围隔离约定：用户侧查询强制限定用户归属范围
 * （EMPLOYEE_USER/EPISODIC/PROCEDURAL），WORKSPACE 与 SESSION 记忆只能经
 * {@link #findByScope(Long, MemoryScope, String, String)} 按单一 scope 查询，禁止跨 scope 混查。
 * 本表未纳入框架租户拦截白名单，用户可达读写必须走 tenantScoped，租户上下文为空失败关闭。
 */
@Repository
public interface AgentMemoryMapper extends SuperMapper<AgentMemory> {

	/**
	 * 用户个人侧记忆归属范围（与 WORKSPACE/SESSION 严格隔离）。
	 */
	List<MemoryScope> USER_OWNED_SCOPES = List.of(MemoryScope.EMPLOYEE_USER, MemoryScope.EPISODIC,
			MemoryScope.PROCEDURAL);

	/**
	 * 查询用户召回候选，强制排除 WORKSPACE/SESSION 记忆，保证用户记忆与共享记忆隔离。
	 */
	default List<AgentMemory> findRecallCandidates(Long agentId, String userId, String tenantId) {
		return selectList(tenantScoped(activeWrapper(), tenantId).eq(AgentMemory::getAgentId, agentId)
			.eq(AgentMemory::getUserId, userId)
			.in(AgentMemory::getSubjectType, USER_OWNED_SCOPES)
			.orderByDesc(AgentMemory::getImportance)
			.orderByDesc(AgentMemory::getConfidence)
			.orderByDesc(AgentMemory::getLastModifyTime));
	}

	/**
	 * PR-7 数字员工无人值守召回候选：仅该数字员工的共享记忆（WORKSPACE scope，subjectId=数字员工ID），
	 * 不召回任何真人记忆（EMPLOYEE_USER/EPISODIC/PROCEDURAL），无发起人的定时任务不得看见用户记忆。
	 */
	default List<AgentMemory> findRecallCandidatesForDigitalEmployee(Long agentId, Long digitalEmployeeId,
			String tenantId) {
		return selectList(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentMemory::getAgentId, agentId)
			.eq(AgentMemory::getSubjectType, MemoryScope.WORKSPACE)
			.eq(AgentMemory::getSubjectId, String.valueOf(digitalEmployeeId))
			.eq(AgentMemory::getDigitalEmployeeId, digitalEmployeeId)
			.orderByDesc(AgentMemory::getImportance)
			.orderByDesc(AgentMemory::getConfidence)
			.orderByDesc(AgentMemory::getLastModifyTime));
	}

	/**
	 * 查询用户个人侧记忆清单，强制排除 WORKSPACE/SESSION 记忆。
	 */
	default List<AgentMemory> findByAgentIdAndUserId(Long agentId, String userId, String tenantId) {
		return findByAgentIdAndUserId(agentId, userId, null, null, null, tenantId);
	}

	/**
	 * 查询用户个人侧记忆清单并按治理维度筛选，强制排除 WORKSPACE/SESSION 记忆。
	 * scope/sensitivity/consentStatus 为空时自动跳过该条件；scope 与用户归属范围取交集，
	 * 传 WORKSPACE/SESSION 查不到数据，范围隔离约定不被绕过。
	 */
	default List<AgentMemory> findByAgentIdAndUserId(Long agentId, String userId, MemoryScope scope,
			MemorySensitivity sensitivity, MemoryConsentStatus consentStatus, String tenantId) {
		return selectList(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentMemory::getAgentId, agentId)
			.eq(AgentMemory::getUserId, userId)
			.in(AgentMemory::getSubjectType, USER_OWNED_SCOPES)
			.eq(AgentMemory::getSubjectType, scope)
			.eq(AgentMemory::getSensitivity, sensitivity)
			.eq(AgentMemory::getConsentStatus, consentStatus)
			.orderByDesc(AgentMemory::getLastModifyTime)
			.orderByDesc(AgentMemory::getId));
	}

	/**
	 * 按单一记忆范围 + 主体查询（scope 必填，禁止跨 scope 混查）。租户谓词恒定生效，
	 * 该表未纳入框架租户拦截白名单，此处的显式租户谓词是唯一防线。
	 */
	default List<AgentMemory> findByScope(Long agentId, MemoryScope scope, String subjectId, String tenantId) {
		LbqWrapper<AgentMemory> wrapper = tenantScoped(activeWrapper(), tenantId)
			.eq(AgentMemory::getSubjectType, scope)
			.eq(AgentMemory::getSubjectId, subjectId);
		if (MemoryScope.WORKSPACE.equals(scope)) {
			// 员工页用员工主键当 agentId；抽取可能写 agent_id=员工ID 或只冗余 digital_employee_id
			wrapper.and(item -> item.eq(AgentMemory::getAgentId, agentId)
				.or()
				.eq(AgentMemory::getDigitalEmployeeId, agentId));
		}
		else {
			wrapper.eq(AgentMemory::getAgentId, agentId);
		}
		return selectList(wrapper.orderByDesc(AgentMemory::getLastModifyTime).orderByDesc(AgentMemory::getId));
	}

	/**
	 * 查询同一主体同一事实键的最大修订版本，不存在时返回 null。
	 */
	default Integer findMaxRevision(Long agentId, MemoryScope scope, String subjectId, String factKey,
			String tenantId) {
		AgentMemory latest = selectOne(tenantScoped(activeWrapper(), tenantId).eq(AgentMemory::getAgentId, agentId)
			.eq(AgentMemory::getSubjectType, scope)
			.eq(AgentMemory::getSubjectId, subjectId)
			.eq(AgentMemory::getFactKey, factKey)
			.orderByDesc(AgentMemory::getRevision)
			.last(" limit 1"));
		return latest == null ? null : latest.getRevision();
	}

	/**
	 * 按语义哈希查询用户在 Agent 下的既有记忆（写入判重）；semanticHash 为空返回 null。
	 */
	default AgentMemory findByAgentIdUserIdAndHash(Long agentId, String userId, String semanticHash, String tenantId) {
		if (!StringUtils.hasText(semanticHash)) {
			return null;
		}
		return selectOne(tenantScoped(activeWrapper(), tenantId).eq(AgentMemory::getAgentId, agentId)
			.eq(AgentMemory::getUserId, userId)
			.eq(AgentMemory::getSemanticHash, semanticHash)
			.last(" limit 1"));
	}

	/**
	 * 按主键 + 租户查询记忆；跨租户按不存在处理。
	 */
	default AgentMemory findByIdAndTenant(Long id, String tenantId) {
		if (id == null) {
			return null;
		}
		return selectOne(tenantScoped(activeWrapper(), tenantId).eq(AgentMemory::getId, id).last(" limit 1"));
	}

	/**
	 * 按主键批量回表，强制租户过滤。向量命中的 id 必须经此方法才能当记忆用。
	 */
	default List<AgentMemory> findByIdsAndTenant(Collection<Long> ids, String tenantId) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
		if (distinct.isEmpty()) {
			return List.of();
		}
		return selectList(tenantScoped(activeWrapper(), tenantId).in(AgentMemory::getId, distinct));
	}

	/**
	 * 更新单条记忆状态，同时校验 Agent 与用户归属，防止越权改他人记忆。
	 */
	default int updateStatus(Long agentId, String userId, Long memoryId, AgentMemoryStatus status, String tenantId) {
		return update(null, tenantScoped(Wraps.lbU(), tenantId).eq(AgentMemory::getId, memoryId)
			.eq(AgentMemory::getAgentId, agentId)
			.eq(AgentMemory::getUserId, userId)
			.set(AgentMemory::getStatus, status)
			.set(AgentMemory::getLastModifyTime, Instant.now()));
	}

	/**
	 * 逻辑删除单条记忆（显式置 deleted=true），同时校验 Agent 与用户归属。
	 */
	default int softDelete(Long agentId, String userId, Long memoryId, String tenantId) {
		return update(null, tenantScoped(Wraps.lbU(), tenantId).eq(AgentMemory::getId, memoryId)
			.eq(AgentMemory::getAgentId, agentId)
			.eq(AgentMemory::getUserId, userId)
			.set(AgentMemory::getDeleted, true)
			.set(AgentMemory::getLastModifyTime, Instant.now()));
	}

	/**
	 * 逻辑删除用户在 Agent 下的全部记忆（用户清空记忆时调用）。
	 */
	default int softDeleteByAgentIdAndUserId(Long agentId, String userId, String tenantId) {
		return update(null, tenantScoped(Wraps.lbU(), tenantId).eq(AgentMemory::getAgentId, agentId)
			.eq(AgentMemory::getUserId, userId)
			.set(AgentMemory::getDeleted, true)
			.set(AgentMemory::getLastModifyTime, Instant.now()));
	}

	/**
	 * 批量记录记忆被召回使用：use_count 自增并刷新最近使用时间；memoryIds 为空时不执行。
	 */
	default void markUsed(List<Long> memoryIds, String tenantId) {
		if (memoryIds == null || memoryIds.isEmpty()) {
			return;
		}
		update(null, tenantScoped(Wraps.lbU(), tenantId).in(AgentMemory::getId, memoryIds)
			.setSql("use_count = COALESCE(use_count, 0) + 1")
			.set(AgentMemory::getLastUsedTime, Instant.now())
			.set(AgentMemory::getLastModifyTime, Instant.now()));
	}

	/**
	 * 按数字员工归属查询共享记忆清单（WORKSPACE scope，subjectId/digitalEmployeeId 双键一致）。
	 */
	default List<AgentMemory> findByDigitalEmployeeId(Long agentId, Long digitalEmployeeId, String tenantId) {
		return selectList(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentMemory::getAgentId, agentId)
			.eq(AgentMemory::getSubjectType, MemoryScope.WORKSPACE)
			.eq(AgentMemory::getDigitalEmployeeId, digitalEmployeeId)
			.orderByDesc(AgentMemory::getLastModifyTime)
			.orderByDesc(AgentMemory::getId));
	}

	/**
	 * 逻辑删除过滤由 {@code @TableLogic} 自动追加，此处不再手写 {@code deleted = false}；
	 * PR-7 起查询构造统一走 Wraps.lbQ()（项目规范禁止 new LambdaQueryWrapper）。
	 */
	private LbqWrapper<AgentMemory> activeWrapper() {
		return Wraps.lbQ();
	}

	/**
	 * 追加租户强制过滤：tenantId 缺失时直接失败关闭，防止 Wraps 跳空后退化成全平台查询。
	 */
	private static LbqWrapper<AgentMemory> tenantScoped(LbqWrapper<AgentMemory> wrapper, String tenantId) {
		requireTenantId(tenantId);
		return wrapper.eq(AgentMemory::getTenantId, tenantId.trim());
	}

	private static LambdaUpdateWrapper<AgentMemory> tenantScoped(LambdaUpdateWrapper<AgentMemory> wrapper,
			String tenantId) {
		requireTenantId(tenantId);
		return wrapper.eq(AgentMemory::getTenantId, tenantId.trim());
	}

	private static void requireTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问记忆");
		}
	}

}
