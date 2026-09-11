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
package com.sn68.agent.dataagent.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.task.dto.AgentTaskRunPageQueryReq;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperService;

/**
 * Agent任务运行服务契约。
 */
public interface AgentTaskRunService extends SuperService<AgentTaskRun> {

	/**
	 * 幂等落库一次任务运行：以幂等键唯一索引兜底，
	 * 冲突时返回已存在的运行记录并标记非新建。
	 */
	ClaimResult claimRun(AgentTaskRun run);

	/**
	 * PR-6 FORBID 并发槽占用（方案A）。FORBID 策略在事务窗口内做 SKIP LOCKED 预检 +
	 * PENDING 行插入，idx_forbid_slot 部分唯一索引做最终仲裁；冲突时区分幂等命中
	 * （返回已有行）与槽位被占（落 SKIPPED 行并带原因码 CONCURRENT_SLOT_LOCKED）。
	 * ALLOW 策略退化为幂等落库，不参与槽竞争。
	 */
	SlotClaim claimSlot(AgentTaskRun run);

	/**
	 * PR-6 RUNNING 推进：运行时开跑后按 runtime_run_id 把 PENDING 推进为 RUNNING
	 * （幂等，仅 PENDING 行命中）。
	 *
	 * @return true 表示本次真正推进了状态
	 */
	boolean markRunningByRuntimeRun(Long runtimeRunId);

	/**
	 * 按任务运行主键直接落终态（无 RuntimeRun 的失败路径：授权失败/等待授权），
	 * 随终态定向释放 FORBID 并发槽镜像。
	 *
	 * @return true 表示本次真正推进了状态
	 */
	boolean markTerminalById(Long id, TaskRunStatus status, String errorMessage);

	/**
	 * 并发槽占用结果。outcome 决定后续动作：CLAIMED 继续挂接 RuntimeRun；
	 * IDEMPOTENT_HIT / SLOT_CONFLICT 不得再挂接（返回既有行或 SKIPPED 台账行）。
	 */
	record SlotClaim(AgentTaskRun run, SlotOutcome outcome) {
	}

	/** 并发槽占用结果分类。 */
	enum SlotOutcome {

		/** 本次成功竞得槽位（新建 PENDING 行）。 */
		CLAIMED,

		/** 命中已有运行（幂等键回查），按幂等语义返回。 */
		IDEMPOTENT_HIT,

		/** FORBID 槽被其他活跃运行占用，本次已落 SKIPPED（原因码 CONCURRENT_SLOT_LOCKED）。 */
		SLOT_CONFLICT

	}

	/**
	 * 分页查询任务定义下的运行记录（管理端）。
	 */
	IPage<AgentTaskRun> pageByDefinition(Long definitionId, AgentTaskRunPageQueryReq request);

	/**
	 * 取消任务运行并释放 FORBID 并发槽。已挂 Runtime 时先请求 Runtime 取消，再把台账标为已取消；
	 * 未挂 Runtime 的 PENDING 直接取消。已是终态则原样返回（幂等）。
	 */
	AgentTaskRun cancel(Long taskRunId, String reason);

	/**
	 * 按关联的运行时 RunID 把任务运行同步到终态。供运行时守护作业在权威 Run 收敛后回同步任务侧，
	 * 使任务台账不会停在 PENDING/RUNNING。幂等：仅 PENDING/RUNNING 的运行会被推进。
	 *
	 * @param runtimeRunId 关联运行时 RunID（agent_task_run.runtime_run_id）
	 * @param status 目标终态
	 * @param errorMessage 失败原因（中文，落 error_message）
	 * @return true 表示本次真正推进了状态；false 表示无关联任务运行或其已是终态
	 */
	boolean markTerminalByRuntimeRun(Long runtimeRunId, TaskRunStatus status, String errorMessage);

	/**
	 * 幂等落库结果。claimed 为 true 表示本次新建，false 表示命中已有记录。
	 */
	record ClaimResult(AgentTaskRun run, boolean claimed) {
	}

}
