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
package com.sn68.agent.dataagent.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 任务定义。Task 绑定 digitalEmployeeId + employeeReleaseId，
 * 二者由数字员工工作流建表，这里只存 Long 外键列。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_task_definition")
@Schema(description = "Agent任务定义实体")
public class AgentTaskDefinition extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "所属数字员工 ID")
	private Long digitalEmployeeId;
	
	@Schema(description = "绑定的数字员工发布版本 ID")
	private Long employeeReleaseId;

	@Schema(description = "任务名称")
	private String taskName;

	@Schema(description = "任务描述")
	private String taskDescription;

	@Schema(description = "任务类型（业务分类，如 REPORT/MONITOR/OPERATION）")
	private String taskType;

	@Schema(description = "默认自治级别（AUTONOMOUS/ASSISTED）")
	private String defaultAutonomyLevel;

	/**
	 * 高风险写任务标记。为 true 时自治级别强制按 ASSISTED 处理（见 AgentTaskDefinitionServiceImpl），
	 * 写动作必须经人工审批后才能执行。
	 */
	@Schema(description = "是否高风险写任务（高风险写任务默认 ASSISTED）")
	private Boolean highRiskWrite;

	/**
	 * 自动任务（SCHEDULE/EVENT/API/IM 触发）执行时使用的受限 service principal 标识，
	 * 禁止使用创建者长期 Token。
	 * PR-6 起拉起前经 EmployeeExecutionContextClient 校验 Principal READY 并换取受限执行凭据，
	 * 失败不创建可执行 Run（WAITING_AUTH / FAILED + AUTHORIZATION_DENIED）。
	 */
	@Schema(description = "受限执行主体标识（service principal）")
	private String servicePrincipal;

	/**
	 * 当前占用 FORBID 并发槽的任务运行ID（agent_task_run.id 镜像）。
	 * launch 竞得槽后 CAS 占用（WHERE active_run_id IS NULL），终态回写时按 runId 定向释放，
	 * 守护作业对账回收残留；正确性由 agent_task_run 上的 idx_forbid_slot 部分唯一索引保证，
	 * 本列只做占用可见性与快速判定，不承担正确性。
	 */
	@Schema(description = "当前占用并发槽的任务运行ID（可空镜像列）")
	private Long activeRunId;

	@Schema(description = "状态（enabled/disabled）")
	private String status;

}
