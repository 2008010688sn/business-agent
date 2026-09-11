/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeApproval;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/**
 * 持久运行时审批 Mapper。查询发生在已校验租户归属的 run 语境内。
 */
@Repository
public interface AgentRuntimeApprovalMapper extends SuperMapper<AgentRuntimeApproval> {

	default List<AgentRuntimeApproval> listByRunId(Long runId) {
		return selectList(Wraps.<AgentRuntimeApproval>lbQ()
			.eq(AgentRuntimeApproval::getRunId, runId)
			.orderByAsc(AgentRuntimeApproval::getId));
	}

	default AgentRuntimeApproval findPendingByRunIdAndStepKey(Long runId, String stepKey) {
		return selectOne(Wraps.<AgentRuntimeApproval>lbQ()
			.eq(AgentRuntimeApproval::getRunId, runId)
			.eq(AgentRuntimeApproval::getStepKey, stepKey)
			.eq(AgentRuntimeApproval::getState, "PENDING")
			.orderByDesc(AgentRuntimeApproval::getId)
			.last("LIMIT 1"));
	}

	default AgentRuntimeApproval findByIdAndTenantId(Long id, String tenantId) {
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentRuntimeApproval::getId, id));
	}

	/**
	 * 按（租户 + 运行 + 审批幂等键 + 状态）查最新一条。
	 *
	 * <p>PR-1 起 approvalKey 已把 owner 与 runId 编码进键，此处再补 runId 独立列谓词是第二道闸：
	 * 即便未来键的拼装口径回退，批复也不会被别的 owner / 别的运行匹配到。参数变化即
	 * paramsHash 变化，旧审批天然不匹配。</p>
	 */
	default AgentRuntimeApproval findLatestByRunAndApprovalKeyAndState(String tenantId, Long runId,
			String approvalKey, String state) {
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentRuntimeApproval::getRunId, runId)
			.eq(AgentRuntimeApproval::getApprovalKey, approvalKey)
			.eq(AgentRuntimeApproval::getState, state)
			.orderByDesc(AgentRuntimeApproval::getId)
			.last("LIMIT 1"));
	}

	/**
	 * 按（租户 + 审批幂等键）查最新一条，不限状态。写工具 ASK 双击时用来复用已决定记录，避免再开一张 PENDING。
	 */
	default AgentRuntimeApproval findLatestByApprovalKey(String tenantId, String approvalKey) {
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentRuntimeApproval::getApprovalKey, approvalKey)
			.orderByDesc(AgentRuntimeApproval::getId)
			.last("LIMIT 1"));
	}

	/**
	 * 一次性消费已通过审批（APPROVED → CONSUMED）。状态谓词 + 过期谓词保证并发下只放行一次、
	 * 过期批复不可消费，属 Wraps 不适用的原生 SQL 场景。
	 */
	@Update("""
			UPDATE agent_runtime_approval
			SET state = 'CONSUMED',
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state = 'APPROVED'
			  AND (expires_at IS NULL OR expires_at > #{now})
			""")
	int consume(@Param("id") Long id, @Param("now") Instant now);

	/**
	 * 作废未消费审批（PENDING/APPROVED → CANCELLED）。已消费或已驳回不命中。
	 */
	@Update("""
			UPDATE agent_runtime_approval
			SET state = 'CANCELLED',
			    last_modify_time = CURRENT_TIMESTAMP,
			    decision_comment = CAST(#{comment, jdbcType=VARCHAR} AS VARCHAR),
			    approver = CAST(#{actor, jdbcType=VARCHAR} AS VARCHAR),
			    decided_at = #{now}
			WHERE id = #{id}
			  AND deleted = false
			  AND state IN ('PENDING','APPROVED')
			""")
	int cancel(@Param("id") Long id, @Param("actor") String actor, @Param("now") Instant now,
			@Param("comment") String comment);

	/**
	 * 懒惰过期判定（读取时过期即置 EXPIRED，不建定时任务）：PENDING/APPROVED 且已过期方可迁移。
	 */
	@Update("""
			UPDATE agent_runtime_approval
			SET state = 'EXPIRED',
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state IN ('PENDING','APPROVED')
			  AND expires_at IS NOT NULL
			  AND expires_at <= #{now}
			""")
	int expire(@Param("id") Long id, @Param("now") Instant now);

	/**
	 * 审批决定（PENDING → APPROVED/REJECTED/EXPIRED/CANCELLED）。
	 * 状态谓词保证一条审批只被决定一次，属 Wraps 不适用的原生 SQL 场景。
	 */
	@Update("""
			UPDATE agent_runtime_approval
			SET state = #{toState},
			    approver = CAST(#{approver, jdbcType=VARCHAR} AS VARCHAR),
			    decided_at = #{decidedAt},
			    decision_comment = CAST(#{decisionComment, jdbcType=VARCHAR} AS VARCHAR),
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state = 'PENDING'
			""")
	int decide(@Param("id") Long id, @Param("toState") String toState, @Param("approver") String approver,
			@Param("decidedAt") Instant decidedAt, @Param("decisionComment") String decisionComment);

	private static LbqWrapper<AgentRuntimeApproval> tenantScoped(LbqWrapper<AgentRuntimeApproval> wrapper,
			String tenantId) {
		if (tenantId == null) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问运行审批");
		}
		return wrapper.eq(AgentRuntimeApproval::getTenantId, tenantId);
	}

}
