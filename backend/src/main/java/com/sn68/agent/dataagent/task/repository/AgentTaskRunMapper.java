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
package com.sn68.agent.dataagent.task.repository;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.task.dto.AgentTaskRunPageQueryReq;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
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
 * Agent任务运行Mapper服务契约。
 */
@Repository
public interface AgentTaskRunMapper extends SuperMapper<AgentTaskRun> {

	/**
	 * 按租户 + 主键查询任务运行。跨租户按不存在处理。
	 */
	default AgentTaskRun findByTenantAndId(String tenantId, Long id) {
		if (!StringUtils.hasText(tenantId) || id == null) {
			return null;
		}
		return selectOne(Wraps.<AgentTaskRun>lbQ()
			.eq(AgentTaskRun::getTenantId, tenantId.trim())
			.eq(AgentTaskRun::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 按幂等键查询运行记录（幂等冲突时取回已有记录返回）。
	 */
	default AgentTaskRun findByIdempotencyKey(String idempotencyKey) {
		if (!StringUtils.hasText(idempotencyKey)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentTaskRun>()
			.eq(AgentTaskRun::getIdempotencyKey, idempotencyKey.trim())
			.last(" limit 1"));
	}

	/**
	 * 查询触发器下未结束（PENDING/RUNNING）的运行，供 FORBID 并发策略判定。
	 */
	default List<AgentTaskRun> findActiveByTrigger(String tenantId, Long triggerId) {
		if (!StringUtils.hasText(tenantId) || triggerId == null) {
			return List.of();
		}
		return selectList(new LambdaQueryWrapper<AgentTaskRun>()
			.eq(AgentTaskRun::getTenantId, tenantId.trim())
			.eq(AgentTaskRun::getTriggerId, triggerId)
			.in(AgentTaskRun::getRunStatus, TaskRunStatus.PENDING.getValue(), TaskRunStatus.RUNNING.getValue()));
	}

	/**
	 * 按关联的运行时 RunID 查询任务运行，供运行终态回写定位台账行并输出带 taskRunId 的可追踪日志。
	 *
	 * <p>查不到表示该 Run 不由任务链路发起（对话、评测等），回写方按「无需同步」跳过。
	 * 本查询只用于定位与日志，<b>不是幂等闸门</b> —— 幂等仍由
	 * {@link #markTerminalByRuntimeRunId} 的状态谓词保证。</p>
	 *
	 * <p>调用方是无租户上下文的系统级链路（守护作业 / Outbox 消费方），关联键 runtime_run_id
	 * 已限定到唯一一次运行，与 {@link #markTerminalByRuntimeRunId} 同口径不带租户谓词。</p>
	 */
	default AgentTaskRun findByRuntimeRunId(Long runtimeRunId) {
		if (runtimeRunId == null) {
			return null;
		}
		return selectOne(Wraps.<AgentTaskRun>lbQ()
			.eq(AgentTaskRun::getRuntimeRunId, runtimeRunId)
			.orderByDesc(AgentTaskRun::getId)
			.last(" limit 1"));
	}

	/**
	 * 按关联的运行时 RunID 把任务运行推进到终态（运行时侧收敛后回同步任务侧）。
	 *
	 * <p>状态谓词 {@code run_status IN ('PENDING','RUNNING')} 兼作幂等闸门：重复同步只有第一次命中，
	 * 且不会覆盖已由其他链路写入的终态（SUCCESS/SKIPPED/CANCELLED）。</p>
	 *
	 * <p>调用方是无租户上下文的系统级守护作业，且关联键 runtime_run_id 已限定到唯一一次运行，
	 * 显式跳过租户与数据权限拦截器。</p>
	 *
	 * @return 受影响行数，0 表示无关联任务运行或其已是终态
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Update("""
			UPDATE agent_task_run
			SET run_status = #{toStatus},
			    error_message = #{errorMessage},
			    finished_time = #{finishedTime},
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE runtime_run_id = #{runtimeRunId}
			  AND deleted = false
			  AND run_status IN ('PENDING','RUNNING')
			""")
	int markTerminalByRuntimeRunId(@Param("runtimeRunId") Long runtimeRunId, @Param("toStatus") String toStatus,
			@Param("errorMessage") String errorMessage, @Param("finishedTime") Instant finishedTime);

	/**
	 * PR-6 FORBID 并发槽预检（清单第 8 条）：查询任务定义下未结束（PENDING/RUNNING）的运行并加行锁，
	 * SKIP LOCKED 让并发预检互不阻塞，锁住的活跃行延迟并发插入窗口；插入竞态的最终仲裁仍是
	 * idx_forbid_slot 部分唯一索引（预检只是减少无效插入，不是唯一闸门）。
	 *
	 * <p>手写 SQL：MP wrapper 不支持 FOR UPDATE SKIP LOCKED 锁子句。必须在事务内调用
	 * （行锁随事务结束释放）；手写 SQL 已带租户谓词，显式跳过租户与数据权限拦截器避免重复拼接。</p>
	 *
	 * @return 活跃运行ID列表；空表示槽位空闲（当前事务窗口内）
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT id FROM agent_task_run
			WHERE tenant_id = #{tenantId}
			  AND definition_id = #{definitionId}
			  AND deleted = false
			  AND run_status IN ('PENDING','RUNNING')
			ORDER BY id
			FOR UPDATE SKIP LOCKED
			""")
	List<Long> lockActiveRunIdsByDefinition(@Param("tenantId") String tenantId,
			@Param("definitionId") Long definitionId);

	/**
	 * 无锁查询任务定义下未结束（PENDING/RUNNING）的运行：唯一索引冲突后的冲突源判定
	 * （非空即槽位被占，转 SKIPPED），以及对账作业的活跃行核对。
	 */
	default List<AgentTaskRun> findActiveByDefinition(String tenantId, Long definitionId) {
		if (!StringUtils.hasText(tenantId) || definitionId == null) {
			return List.of();
		}
		return selectList(Wraps.<AgentTaskRun>lbQ()
			.eq(AgentTaskRun::getTenantId, tenantId.trim())
			.eq(AgentTaskRun::getDefinitionId, definitionId)
			.in(AgentTaskRun::getRunStatus, TaskRunStatus.PENDING.getValue(), TaskRunStatus.RUNNING.getValue()));
	}

	/**
	 * PR-6 RUNNING 推进（清单第 10 条）：运行时侧已开跑（RUN_STARTED 不走 Outbox），
	 * 任务侧按 runtime_run_id 对账推进 PENDING → RUNNING。
	 *
	 * <p>状态谓词 {@code run_status = 'PENDING'} 兼作幂等闸门：重复同步只有第一次命中，
	 * RUNNING/SKIPPED/终态行不受影响；started_time 取 COALESCE 保留首次推进时刻。</p>
	 *
	 * <p>调用方是无租户上下文的系统级对账作业，关联键 runtime_run_id 已限定到唯一一次运行，
	 * 显式跳过租户与数据权限拦截器。</p>
	 *
	 * @return 受影响行数，0 表示无关联任务运行或其已不在 PENDING
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Update("""
			UPDATE agent_task_run
			SET run_status = 'RUNNING',
			    started_time = COALESCE(started_time, #{startedTime}),
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE runtime_run_id = #{runtimeRunId}
			  AND deleted = false
			  AND run_status = 'PENDING'
			""")
	int markRunningByRuntimeRunId(@Param("runtimeRunId") Long runtimeRunId, @Param("startedTime") Instant startedTime);

	/**
	 * 按主键把任务运行推进到终态（无 runtime_run_id 的失败路径专用：授权失败/等待授权直接落终态）。
	 *
	 * <p>状态谓词 {@code run_status IN ('PENDING','RUNNING')} 兼作幂等闸门，不覆盖其他链路
	 * 已写入的终态。调用方含无租户上下文的守护作业，主键定位 + 显式跳过拦截器。</p>
	 *
	 * @return 受影响行数，0 表示行不存在或已是终态
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Update("""
			UPDATE agent_task_run
			SET run_status = #{toStatus},
			    error_message = #{errorMessage},
			    finished_time = #{finishedTime},
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND run_status IN ('PENDING','RUNNING')
			""")
	int markTerminalById(@Param("id") Long id, @Param("toStatus") String toStatus,
			@Param("errorMessage") String errorMessage, @Param("finishedTime") Instant finishedTime);

	/**
	 * PR-6 对账扫描：PENDING 且已挂运行时 Run 的任务运行（TaskRunStatusSyncJob 逐行核对
	 * 运行时状态后推进 RUNNING 或兜底终态）。与 {@link #findByRuntimeRunId} 同口径，
	 * 调用方为无租户上下文的系统级守护作业，跨租户扫描是有意行为。
	 */
	default List<AgentTaskRun> findPendingWithRuntime(int limit) {
		return selectList(Wraps.<AgentTaskRun>lbQ()
			.eq(AgentTaskRun::getRunStatus, TaskRunStatus.PENDING.getValue())
			.isNotNull(AgentTaskRun::getRuntimeRunId)
			.orderByAsc(AgentTaskRun::getId)
			.last(" limit " + Math.max(1, limit)));
	}

	/**
	 * PR-6 拉起中断僵尸扫描：PENDING 且未挂 RuntimeRun 且创建早于指定时刻的行
	 * （受理后服务崩溃/中断速留下，对账作业收敛 FAILED——重启恢复语义）。
	 */
	default List<AgentTaskRun> findPendingWithoutRuntime(Instant olderThan, int limit) {
		if (olderThan == null) {
			return List.of();
		}
		return selectList(Wraps.<AgentTaskRun>lbQ()
			.eq(AgentTaskRun::getRunStatus, TaskRunStatus.PENDING.getValue())
			.isNull(AgentTaskRun::getRuntimeRunId)
			.le(AgentTaskRun::getCreateTime, olderThan)
			.orderByAsc(AgentTaskRun::getId)
			.last(" limit " + Math.max(1, limit)));
	}

	/**
	 * 分页查询任务定义下的运行记录（管理端，租户必填）。
	 */
	default IPage<AgentTaskRun> selectPageByDefinition(IPage<AgentTaskRun> page, String tenantId, Long definitionId,
			AgentTaskRunPageQueryReq request) {
		if (!StringUtils.hasText(tenantId) || definitionId == null) {
			return page;
		}
		AgentTaskRunPageQueryReq query = request == null ? new AgentTaskRunPageQueryReq() : request;
		String triggerType = StringUtils.hasText(query.getTriggerType())
				? query.getTriggerType().trim().toUpperCase() : null;
		String runStatus = StringUtils.hasText(query.getRunStatus()) ? query.getRunStatus().trim().toUpperCase() : null;
		// 占用中的 PENDING/RUNNING 置顶，避免被后续 SKIPPED 挤出第一页。
		return selectPage(page, Wraps.<AgentTaskRun>lbQ()
			.eq(AgentTaskRun::getTenantId, tenantId.trim())
			.eq(AgentTaskRun::getDefinitionId, definitionId)
			.eq(AgentTaskRun::getTriggerId, query.getTriggerId())
			.eq(AgentTaskRun::getTriggerType, triggerType)
			.eq(AgentTaskRun::getRunStatus, runStatus)
			.last("ORDER BY CASE WHEN run_status IN ('PENDING','RUNNING') THEN 0 ELSE 1 END, create_time DESC, id DESC"));
	}

}
