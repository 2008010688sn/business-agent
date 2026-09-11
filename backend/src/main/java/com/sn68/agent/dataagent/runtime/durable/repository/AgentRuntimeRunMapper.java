/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 持久运行时 Run Mapper。
 *
 * <p>CAS / 租约 / 取消纪元使用注解原生 SQL 而非 Wraps：这些语句依赖
 * {@code state_version = state_version + 1}、{@code fence_token + 1}、{@code RETURNING}
 * 等数据库端原子表达式，Wraps 只能绑定客户端已计算的值，无法表达该语义。
 * 本模块租户插件为白名单制且不含本表，凡业务入口查询必须显式携带 tenant_id 条件。</p>
 */
@Repository
public interface AgentRuntimeRunMapper extends SuperMapper<AgentRuntimeRun> {

	/**
	 * PR-1 幂等创建查询：按 (tenantId, ownerType, ownerId, clientRequestId) 唯一键取已有 Run。
	 * 四列与唯一索引 agent_runtime_run_uk_tenant_owner_client_request 完全对齐，
	 * 唯一冲突后回查必然命中本租户的 winner。
	 */
	default AgentRuntimeRun findByOwnerAndClientRequest(String tenantId, String ownerType, Long ownerId,
			String clientRequestId) {
		return selectOne(Wraps.<AgentRuntimeRun>lbQ()
			.eq(AgentRuntimeRun::getTenantId, tenantId)
			.eq(AgentRuntimeRun::getOwnerType, ownerType)
			.eq(AgentRuntimeRun::getOwnerId, ownerId)
			.eq(AgentRuntimeRun::getClientRequestId, clientRequestId)
			.last("LIMIT 1"));
	}

	default AgentRuntimeRun findByIdAndTenantId(Long id, String tenantId) {
		return selectOne(Wraps.<AgentRuntimeRun>lbQ()
			.eq(AgentRuntimeRun::getId, id)
			.eq(AgentRuntimeRun::getTenantId, tenantId)
			.last("LIMIT 1"));
	}

	/**
	 * 本租户本会话当前非终态 CHAT Run。部分唯一索引
	 * {@code agent_runtime_run_uk_chat_thread_active} 保证最多一行。
	 */
	default AgentRuntimeRun findActiveChatByTenantAndThread(String tenantId, String threadId) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(threadId)) {
			return null;
		}
		return selectOne(Wraps.<AgentRuntimeRun>lbQ()
			.eq(AgentRuntimeRun::getTenantId, tenantId)
			.eq(AgentRuntimeRun::getThreadId, threadId)
			.eq(AgentRuntimeRun::getRunMode, "CHAT")
			.in(AgentRuntimeRun::getState, List.of("PENDING", "RUNNING", "WAITING_APPROVAL", "WAITING_INPUT",
					"CANCELLING"))
			.orderByDesc(AgentRuntimeRun::getId)
			.last("LIMIT 1"));
	}

	/**
	 * 按员工与创建时间窗口列出运行（每日汇总）。
	 */
	default List<AgentRuntimeRun> listByEmployeeCreatedBetween(String tenantId, Long employeeId, Instant from,
			Instant to) {
		if (tenantId == null || employeeId == null || from == null || to == null) {
			return List.of();
		}
		return selectList(Wraps.<AgentRuntimeRun>lbQ()
			.eq(AgentRuntimeRun::getTenantId, tenantId)
			.eq(AgentRuntimeRun::getDigitalEmployeeId, employeeId)
			.ge(AgentRuntimeRun::getCreateTime, from)
			.lt(AgentRuntimeRun::getCreateTime, to)
			.orderByAsc(AgentRuntimeRun::getId));
	}

	/**
	 * 系统级路径：runtimeRequestId 为全局唯一 UUID，由内存注册表取消写穿时调用，
	 * 该链路发生在无用户会话的运行时线程上，无法获取租户上下文，因此不带 tenant_id 条件。
	 */
	default AgentRuntimeRun findByRuntimeRequestId(String runtimeRequestId) {
		return selectOne(Wraps.<AgentRuntimeRun>lbQ()
			.eq(AgentRuntimeRun::getRuntimeRequestId, runtimeRequestId)
			.orderByDesc(AgentRuntimeRun::getId)
			.last("LIMIT 1"));
	}

	/**
	 * 系统级路径：双写迁移期按遥测表 agent_orchestration_run.id 关联权威 Run，
	 * 调用点位于编排运行时内部（已按遥测 Run 校验过归属），不带 tenant_id 条件。
	 */
	default AgentRuntimeRun findBySourceRunId(Long sourceRunId) {
		return selectOne(Wraps.<AgentRuntimeRun>lbQ()
			.eq(AgentRuntimeRun::getSourceRunId, sourceRunId)
			.orderByDesc(AgentRuntimeRun::getId)
			.last("LIMIT 1"));
	}

	/**
	 * 守护作业扫描：需要（重新）交给单步任务执行体驱动的运行。
	 *
	 * <p>命中两类行，两者是同一张表的不同状态，合并扫描避免重复遍历：</p>
	 * <ul>
	 * <li>PENDING：从未被拉起过的运行（任务链路建 Run 后无人拉起）。</li>
	 * <li>RUNNING 且 last_modify_time 早于 {@code staleBefore}：已启动但停摆的运行，
	 * 典型来源是审批通过后 resume 回 RUNNING 却无人续跑、以及线程池拒绝导致就绪步骤丢失。
	 * 用停摆窗口避开刚被其他副本置为 RUNNING、步骤尚未物化的瞬时窗口。</li>
	 * </ul>
	 *
	 * <p>限定条件缺一不可：{@code NOT EXISTS} 排除已有步骤在执行中/等待交互的运行（不重复拉起在途执行）；
	 * {@code source_run_id IS NULL} 排除双写迁移期的编排镜像运行（其步骤由 EventDrivenCollaboratorEngine
	 * 的执行体驱动，用单步执行体拉起会跑错语义）；{@code run_mode} 限定为单轮语义的运行模式；
	 * 已过 {@code deadline_at} 的行不返回，由 {@link #listExpiredDeadlineRuns} 收敛为超时终态。</p>
	 *
	 * <p>系统级跨租户扫描，无租户上下文，显式跳过租户与数据权限拦截器。</p>
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT r.* FROM agent_runtime_run r
			WHERE r.deleted = false
			  AND r.source_run_id IS NULL
			  AND r.run_mode IN ('AGENT_LOOP','DIRECT')
			  AND (r.deadline_at IS NULL OR r.deadline_at > #{now})
			  AND (r.state = 'PENDING' OR (r.state = 'RUNNING' AND r.last_modify_time < #{staleBefore}))
			  AND NOT EXISTS (
			      SELECT 1 FROM agent_runtime_step s
			      WHERE s.run_id = r.id AND s.deleted = false AND s.state IN ('RUNNING','WAITING'))
			ORDER BY r.id
			LIMIT #{limit}
			""")
	List<AgentRuntimeRun> listStartableRuns(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore,
			@Param("limit") int limit);

	/**
	 * 守护作业扫描：已过绝对截止时间却仍未进入终态、且当前无节点在执行的运行，交由作业收敛为超时终态。
	 *
	 * <p>任务型（AGENT_LOOP/DIRECT）与会话 CHAT 共用截止时刻，谓词不同：</p>
	 * <ul>
	 * <li>任务型排除 CANCELLING（交给取消链路）及持有效步骤租约的在途执行。</li>
	 * <li>CHAT 排除 WAITING_*（独立 WAIT_TIMEOUT）；执行中看 Run 租约，过期才收敛；
	 * CANCELLING 纳入扫描以免占死一会话一轮槽。</li>
	 * </ul>
	 *
	 * <p>系统级跨租户扫描，无租户上下文，显式跳过租户与数据权限拦截器。</p>
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT r.* FROM agent_runtime_run r
			WHERE r.deleted = false
			  AND r.source_run_id IS NULL
			  AND r.deadline_at IS NOT NULL
			  AND r.deadline_at <= #{now}
			  AND (
			    (r.run_mode IN ('AGENT_LOOP','DIRECT')
			     AND r.state NOT IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','CANCELLING')
			     AND NOT EXISTS (
			         SELECT 1 FROM agent_runtime_step s
			         WHERE s.run_id = r.id AND s.deleted = false
			           AND s.state IN ('RUNNING','WAITING')
			           AND s.lease_until IS NOT NULL AND s.lease_until > #{now}))
			    OR
			    (r.run_mode = 'CHAT'
			     AND r.state IN ('PENDING','RUNNING','CANCELLING')
			     AND (r.lease_until IS NULL OR r.lease_until <= #{now}))
			  )
			ORDER BY r.id
			LIMIT #{limit}
			""")
	List<AgentRuntimeRun> listExpiredDeadlineRuns(@Param("now") Instant now, @Param("limit") int limit);

	/**
	 * CHAT 等待态独立长超时：WAITING_* 不吃执行 deadline，用户不回来则按 last_modify_time 收敛，
	 * 释放一会话一轮槽位。系统级跨租户扫描。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT r.* FROM agent_runtime_run r
			WHERE r.deleted = false
			  AND r.source_run_id IS NULL
			  AND r.run_mode = 'CHAT'
			  AND r.state IN ('WAITING_INPUT','WAITING_APPROVAL')
			  AND r.last_modify_time IS NOT NULL
			  AND r.last_modify_time <= #{staleBefore}
			ORDER BY r.id
			LIMIT #{limit}
			""")
	List<AgentRuntimeRun> listExpiredWaitingChatRuns(@Param("staleBefore") Instant staleBefore,
			@Param("limit") int limit);

	/**
	 * CAS 状态推进：state_version 相等才允许更新并自增；携带 fence_token 时旧 fence 不得写入；
	 * 终态永不被覆盖。返回受影响行数，0 表示竞争失败（版本过期、fence 过期或已入终态）。
	 * {@code deadlineAt} 为空时保留原 {@code deadline_at}。
	 */
	@Update("""
			UPDATE agent_runtime_run
			SET state = #{toState},
			    state_version = state_version + 1,
			    final_answer = COALESCE(CAST(#{finalAnswer, jdbcType=VARCHAR} AS TEXT), final_answer),
			    error_code = COALESCE(CAST(#{errorCode, jdbcType=VARCHAR} AS VARCHAR), error_code),
			    error_message = COALESCE(CAST(#{errorMessage, jdbcType=VARCHAR} AS TEXT), error_message),
			    started_at = COALESCE(started_at, CAST(#{startedAt, jdbcType=TIMESTAMP} AS TIMESTAMP)),
			    finished_at = COALESCE(CAST(#{finishedAt, jdbcType=TIMESTAMP} AS TIMESTAMP), finished_at),
			    deadline_at = COALESCE(CAST(#{deadlineAt, jdbcType=TIMESTAMP} AS TIMESTAMP), deadline_at),
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state_version = #{expectedVersion}
			  AND (CAST(#{fenceToken, jdbcType=BIGINT} AS BIGINT) IS NULL OR fence_token = #{fenceToken, jdbcType=BIGINT})
			  AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')
			""")
	int casState(@Param("id") Long id, @Param("expectedVersion") Long expectedVersion,
			@Param("fenceToken") Long fenceToken, @Param("toState") String toState,
			@Param("finalAnswer") String finalAnswer, @Param("errorCode") String errorCode,
			@Param("errorMessage") String errorMessage, @Param("startedAt") Instant startedAt,
			@Param("finishedAt") Instant finishedAt, @Param("deadlineAt") Instant deadlineAt);

	/**
	 * 获取或接管租约：租约空缺、已过期或本就属于自己时成功，fence_token 原子递增，
	 * 保证同一时刻只有一个节点持有效 fence（两个节点同时认领只能一个成功）。
	 * 返回新 fence_token，未获取到返回 null。
	 */
	@Select("""
			UPDATE agent_runtime_run
			SET lease_owner = #{owner},
			    lease_until = #{until},
			    fence_token = fence_token + 1,
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')
			  AND (lease_owner IS NULL OR lease_until IS NULL OR lease_until < #{now} OR lease_owner = #{owner})
			RETURNING fence_token
			""")
	Long acquireLease(@Param("id") Long id, @Param("owner") String owner, @Param("until") Instant until,
			@Param("now") Instant now);

	/**
	 * 续期租约：仅当前持有者且 fence 未被接管时成功，不递增 fence。
	 */
	@Update("""
			UPDATE agent_runtime_run
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
	 * 释放租约：仅当前持有者且 fence 匹配时清空，避免释放掉他人新租约。
	 */
	@Update("""
			UPDATE agent_runtime_run
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
	 * 取消纪元递增：非终态运行才可递增；执行方对比自身纪元快照即可发现取消。
	 * 返回递增后的纪元，未命中（已终态或不存在）返回 null。
	 */
	@Select("""
			UPDATE agent_runtime_run
			SET cancellation_epoch = cancellation_epoch + 1,
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')
			RETURNING cancellation_epoch
			""")
	Long bumpCancellationEpoch(@Param("id") Long id);

}
