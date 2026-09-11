/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInvocation;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeInvocationState;
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
 * 持久运行时外部副作用调用 Mapper。
 *
 * <p>状态迁移使用注解原生 SQL：需要「fromState 匹配才允许迁移 + state_version 自增」的 CAS 语义，
 * Wraps 不适用。查询发生在已校验租户归属的 run 语境内。</p>
 */
@Repository
public interface AgentRuntimeInvocationMapper extends SuperMapper<AgentRuntimeInvocation> {

	default AgentRuntimeInvocation findByRunIdAndIdempotencyKey(Long runId, String idempotencyKey) {
		return selectOne(Wraps.<AgentRuntimeInvocation>lbQ()
			.eq(AgentRuntimeInvocation::getRunId, runId)
			.eq(AgentRuntimeInvocation::getIdempotencyKey, idempotencyKey)
			.last("LIMIT 1"));
	}

	default List<AgentRuntimeInvocation> listByRunId(Long runId) {
		return selectList(Wraps.<AgentRuntimeInvocation>lbQ()
			.eq(AgentRuntimeInvocation::getRunId, runId)
			.orderByAsc(AgentRuntimeInvocation::getId));
	}

	/** 管理端对账入口的租户归属校验：本表租户列由网关按 Run 的真实租户落库。 */
	default AgentRuntimeInvocation findByIdAndTenantId(Long id, String tenantId) {
		return selectOne(tenantScoped(Wraps.lbQ(), tenantId)
			.eq(AgentRuntimeInvocation::getId, id)
			.last("LIMIT 1"));
	}

	/**
	 * 待对账清单：结果未知与对账中的调用。这类幂等键在收敛前禁止自动重试，
	 * 必须由运维按外部系统实际结果裁决，故需要一个可发现的列表入口。
	 */
	default List<AgentRuntimeInvocation> listPendingReconcile(String tenantId, int limit) {
		return selectList(tenantScoped(Wraps.lbQ(), tenantId)
			.in(AgentRuntimeInvocation::getState, RuntimeInvocationState.OUTCOME_UNKNOWN.getValue(),
					RuntimeInvocationState.RECONCILING.getValue())
			.orderByAsc(AgentRuntimeInvocation::getId)
			.last("LIMIT " + limit));
	}

	/**
	 * CAS 状态迁移：仅 fromState 匹配的行可迁移到 toState，state_version 自增。
	 * 返回受影响行数，0 表示当前状态已不允许该迁移。
	 */
	@Update("""
			UPDATE agent_runtime_invocation
			SET state = #{toState},
			    state_version = state_version + 1,
			    response_digest = COALESCE(CAST(#{responseDigest, jdbcType=VARCHAR} AS VARCHAR), response_digest),
			    side_effect_receipt = COALESCE(CAST(#{sideEffectReceipt, jdbcType=VARCHAR} AS JSONB), side_effect_receipt),
			    error_code = COALESCE(CAST(#{errorCode, jdbcType=VARCHAR} AS VARCHAR), error_code),
			    error_message = COALESCE(CAST(#{errorMessage, jdbcType=VARCHAR} AS TEXT), error_message),
			    sent_at = COALESCE(CAST(#{sentAt, jdbcType=TIMESTAMP} AS TIMESTAMP), sent_at),
			    completed_at = COALESCE(CAST(#{completedAt, jdbcType=TIMESTAMP} AS TIMESTAMP), completed_at),
			    reconciled_at = COALESCE(CAST(#{reconciledAt, jdbcType=TIMESTAMP} AS TIMESTAMP), reconciled_at),
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state = #{fromState}
			""")
	int casTransition(@Param("id") Long id, @Param("fromState") String fromState, @Param("toState") String toState,
			@Param("responseDigest") String responseDigest, @Param("sideEffectReceipt") String sideEffectReceipt,
			@Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
			@Param("sentAt") Instant sentAt, @Param("completedAt") Instant completedAt,
			@Param("reconciledAt") Instant reconciledAt);

	/**
	 * 授权决策审计列回填（PR-3c）：仅首次（decision_id 为空）写入，重复调用不覆盖，
	 * 保持「一次调用只挂首次判定」的审计口径。属旁路观测写入，失败由调用方 quiet 吞掉。
	 */
	@Update("""
			UPDATE agent_runtime_invocation
			SET decision_id = CAST(#{decisionId, jdbcType=VARCHAR} AS VARCHAR),
			    policy_hash = CAST(#{policyHash, jdbcType=VARCHAR} AS VARCHAR),
			    subject_kind = CAST(#{subjectKind, jdbcType=VARCHAR} AS VARCHAR),
			    reason_code = CAST(#{reasonCode, jdbcType=VARCHAR} AS VARCHAR),
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND decision_id IS NULL
			""")
	int attachDecision(@Param("id") Long id, @Param("decisionId") String decisionId,
			@Param("policyHash") String policyHash, @Param("subjectKind") String subjectKind,
			@Param("reasonCode") String reasonCode);

	private static LbqWrapper<AgentRuntimeInvocation> tenantScoped(LbqWrapper<AgentRuntimeInvocation> wrapper,
			String tenantId) {
		if (tenantId == null) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问运行调用");
		}
		return wrapper.eq(AgentRuntimeInvocation::getTenantId, tenantId);
	}

}
