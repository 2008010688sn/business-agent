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
package com.sn68.agent.dataagent.authorization.observability.repository;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.sn68.agent.dataagent.authorization.observability.dto.ShadowComparisonAggregate;
import com.sn68.agent.dataagent.authorization.observability.dto.ShadowMismatchSample;
import com.sn68.agent.dataagent.authorization.observability.dto.TaskSkipAggregate;
import com.sn68.agent.dataagent.authorization.observability.dto.WorkItemLatencyAggregate;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

/**
 * 授权观测只读聚合 Mapper（PR-10 差异报告 / SKIPPED 观测 / 受理延迟聚合）。
 *
 * <p>系统级观测视角：跨租户聚合（PR-9 灰度门禁需按租户汇总全量比对结果），
 * 与任务对账作业（AgentTaskRunMapper）同款 {@code @InterceptorIgnore} 系统级通道；
 * 全部查询只读、走 {@code agent_runtime_event_idx_tenant_authz} 既有部分索引，
 * 不写任何表。比对状态取自 {@code detailed_decision_log->>'comparisonStatus'}，
 * 明细缺失（历史空 JSON）按 ORIGINAL_ONLY 口径归并（无现网结论输入）。</p>
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Repository
public interface AuthorizationObservabilityMapper {

	/**
	 * 按租户 × 比对状态聚合授权决策影子事件（差异报告主查询）。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT tenant_id AS tenantId,
			       COALESCE(detailed_decision_log->>'comparisonStatus', 'ORIGINAL_ONLY') AS comparisonStatus,
			       COUNT(*) AS total
			FROM agent_runtime_event
			WHERE event_type = 'AUTHORIZATION_DECISION'
			  AND deleted = false
			  AND occurred_at >= #{from} AND occurred_at < #{to}
			GROUP BY tenant_id, COALESCE(detailed_decision_log->>'comparisonStatus', 'ORIGINAL_ONLY')
			""")
	List<ShadowComparisonAggregate> aggregateShadowComparison(@Param("from") Instant from, @Param("to") Instant to);

	/**
	 * MISMATCHED 差异样本明细（清单钦定四字段：decision_id/policy_hash/comparison_status/
	 * comparison_timestamp，另带租户与原因码定位），按时间倒序限量。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT decision_id AS decisionId,
			       tenant_id AS tenantId,
			       policy_hash AS policyHash,
			       reason_code AS reasonCode,
			       COALESCE(detailed_decision_log->>'comparisonStatus', 'ORIGINAL_ONLY') AS comparisonStatus,
			       occurred_at AS comparisonTimestamp
			FROM agent_runtime_event
			WHERE event_type = 'AUTHORIZATION_DECISION'
			  AND deleted = false
			  AND occurred_at >= #{from} AND occurred_at < #{to}
			  AND detailed_decision_log->>'comparisonStatus' = 'MISMATCHED'
			ORDER BY occurred_at DESC
			LIMIT #{limit}
			""")
	List<ShadowMismatchSample> listMismatchSamples(@Param("from") Instant from, @Param("to") Instant to,
			@Param("limit") int limit);

	/**
	 * 按租户 × 跳过原因聚合 SKIPPED 台账行（槽位冲突观测：CONCURRENT_SLOT_LOCKED 归 SLOT_CONFLICT）。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT tenant_id AS tenantId,
			       CASE WHEN error_message LIKE '[CONCURRENT_SLOT_LOCKED]%' THEN 'SLOT_CONFLICT' ELSE 'OTHER' END AS skipReason,
			       COUNT(*) AS total
			FROM agent_task_run
			WHERE run_status = 'SKIPPED'
			  AND deleted = false
			  AND create_time >= #{from} AND create_time < #{to}
			GROUP BY tenant_id, CASE WHEN error_message LIKE '[CONCURRENT_SLOT_LOCKED]%' THEN 'SLOT_CONFLICT' ELSE 'OTHER' END
			""")
	List<TaskSkipAggregate> aggregateTaskSkips(@Param("from") Instant from, @Param("to") Instant to);

	/**
	 * 任务受理延迟 P95（create_time - scheduled_time 的窗口 P95，秒；只统计正向延迟样本）。
	 * WorkItem 创建延迟 P95 &lt;200ms 的 SLO/告警报表侧数据源（受理路径 Timer 埋点属 task 域）。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (create_time - scheduled_time))), 0) AS p95Seconds,
			       COUNT(*) AS sampleTotal
			FROM agent_task_run
			WHERE deleted = false
			  AND scheduled_time IS NOT NULL
			  AND create_time >= #{from} AND create_time < #{to}
			  AND create_time >= scheduled_time
			""")
	WorkItemLatencyAggregate aggregateWorkItemCreateLatency(@Param("from") Instant from, @Param("to") Instant to);

}
