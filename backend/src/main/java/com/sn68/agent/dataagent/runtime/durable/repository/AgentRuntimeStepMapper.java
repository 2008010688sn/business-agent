/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/**
 * 持久运行时 Step Mapper。
 *
 * <p>CAS / 租约使用注解原生 SQL 而非 Wraps：语句依赖 state_version 自增、fence_token 自增与
 * RETURNING 原子表达式，Wraps 无法表达。步骤查询均发生在已经完成租户校验的 run 语境内，
 * 查询条件以 run_id 为主；来自 Controller 的查询在 Service 层先按租户校验 run 归属。</p>
 */
@Repository
public interface AgentRuntimeStepMapper extends SuperMapper<AgentRuntimeStep> {

	default List<AgentRuntimeStep> listByRunId(Long runId) {
		return selectList(Wraps.<AgentRuntimeStep>lbQ()
			.eq(AgentRuntimeStep::getRunId, runId)
			.orderByAsc(AgentRuntimeStep::getId));
	}

	default AgentRuntimeStep findByRunIdAndStepKey(Long runId, String stepKey) {
		return selectOne(Wraps.<AgentRuntimeStep>lbQ()
			.eq(AgentRuntimeStep::getRunId, runId)
			.eq(AgentRuntimeStep::getStepKey, stepKey)
			.last("LIMIT 1"));
	}

	/**
	 * 守护作业扫描：租约已过期的 RUNNING 步骤 ID（执行节点崩溃/长时间失联后遗留）。
	 *
	 * <p>只返回 ID，接管方按 ID 重读整行走 MyBatis-Plus 映射，避免拿扫描时的陈旧快照做 CAS。
	 * 限定条件与 {@link AgentRuntimeRunMapper#listStartableRuns} 同源：只接管单步任务型
	 * 运行的步骤，编排镜像运行（source_run_id 非空）的步骤由其引擎自己的看护逻辑收敛。
	 * 运行状态必须是 RUNNING —— 等待审批/等待输入的运行不得被接管续跑，否则会绕过审批闸门。</p>
	 *
	 * <p>系统级跨租户扫描，显式跳过租户与数据权限拦截器。</p>
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT s.id FROM agent_runtime_step s
			JOIN agent_runtime_run r ON r.id = s.run_id AND r.deleted = false
			WHERE s.deleted = false
			  AND s.state = 'RUNNING'
			  AND s.lease_until IS NOT NULL
			  AND s.lease_until < #{now}
			  AND r.state = 'RUNNING'
			  AND r.source_run_id IS NULL
			  AND r.run_mode IN ('AGENT_LOOP','DIRECT')
			  AND (r.deadline_at IS NULL OR r.deadline_at > #{now})
			ORDER BY s.lease_until
			LIMIT #{limit}
			""")
	List<Long> listExpiredLeaseStepIds(@Param("now") Instant now, @Param("limit") int limit);

	/**
	 * CAS 状态推进：state_version 相等才允许更新并自增；携带 fence_token 时旧 fence 不得写入；
	 * 终态永不被覆盖（取消与成功竞态下终态只有一个有效结果）。返回受影响行数，0 表示竞争失败。
	 */
	@Update("""
			UPDATE agent_runtime_step
			SET state = #{toState},
			    state_version = state_version + 1,
			    error_code = COALESCE(CAST(#{errorCode, jdbcType=VARCHAR} AS VARCHAR), error_code),
			    error_message = COALESCE(CAST(#{errorMessage, jdbcType=VARCHAR} AS TEXT), error_message),
			    output_artifact_id = COALESCE(CAST(#{outputArtifactId, jdbcType=BIGINT} AS BIGINT), output_artifact_id),
			    started_at = COALESCE(started_at, CAST(#{startedAt, jdbcType=TIMESTAMP} AS TIMESTAMP)),
			    finished_at = COALESCE(CAST(#{finishedAt, jdbcType=TIMESTAMP} AS TIMESTAMP), finished_at),
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state_version = #{expectedVersion}
			  AND (CAST(#{fenceToken, jdbcType=BIGINT} AS BIGINT) IS NULL OR fence_token = #{fenceToken, jdbcType=BIGINT})
			  AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','SKIPPED')
			""")
	int casState(@Param("id") Long id, @Param("expectedVersion") Long expectedVersion,
			@Param("fenceToken") Long fenceToken, @Param("toState") String toState,
			@Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
			@Param("outputArtifactId") Long outputArtifactId, @Param("startedAt") Instant startedAt,
			@Param("finishedAt") Instant finishedAt);

	/**
	 * 获取或接管步骤租约：fence_token 原子递增，两个节点同时认领同一步骤只能一个获得有效 fence；
	 * 节点执行中退出后 lease_until 过期，其他节点可携新 fence 接管恢复。
	 * 返回新 fence_token，未获取到返回 null。
	 */
	@Select("""
			UPDATE agent_runtime_step
			SET lease_owner = #{owner},
			    lease_until = #{until},
			    fence_token = fence_token + 1,
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','SKIPPED')
			  AND (lease_owner IS NULL OR lease_until IS NULL OR lease_until < #{now} OR lease_owner = #{owner})
			RETURNING fence_token
			""")
	Long acquireLease(@Param("id") Long id, @Param("owner") String owner, @Param("until") Instant until,
			@Param("now") Instant now);

	/**
	 * 续期步骤租约：仅当前持有者且 fence 未被接管时成功，不递增 fence。
	 */
	@Update("""
			UPDATE agent_runtime_step
			SET lease_until = #{until},
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND lease_owner = #{owner}
			  AND fence_token = #{fenceToken}
			""")
	int renewLease(@Param("id") Long id, @Param("owner") String owner, @Param("fenceToken") Long fenceToken,
			@Param("until") Instant until);

	/**
	 * 释放步骤租约：仅当前持有者且 fence 匹配时清空，避免释放掉他人新租约；不回退 fence_token，
	 * 因此接管者「提高 fence 使旧持有者失效」的效果在释放后依然成立。
	 */
	@Update("""
			UPDATE agent_runtime_step
			SET lease_owner = NULL,
			    lease_until = NULL,
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND lease_owner = #{owner}
			  AND fence_token = #{fenceToken}
			""")
	int releaseLease(@Param("id") Long id, @Param("owner") String owner, @Param("fenceToken") Long fenceToken);

	/**
	 * 原子分配尝试序号：attempt_count 自增并返回，配合 (step_id, attempt_no) 唯一约束保证不重号。
	 */
	@Select("""
			UPDATE agent_runtime_step
			SET attempt_count = attempt_count + 1,
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			RETURNING attempt_count
			""")
	Integer allocateAttemptNo(@Param("id") Long id);

	/**
	 * 同步取消纪元快照到步骤行，供执行方比对识别取消。
	 */
	@Update("""
			UPDATE agent_runtime_step
			SET cancellation_epoch = #{cancellationEpoch},
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND cancellation_epoch < #{cancellationEpoch}
			""")
	int syncCancellationEpoch(@Param("id") Long id, @Param("cancellationEpoch") Long cancellationEpoch);

}
