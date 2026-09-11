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
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Agent编排Step实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_orchestration_step")
@Schema(description = "DataAgent编排步骤记录")
public class AgentOrchestrationStep extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "编排运行记录ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long runId;

	@Schema(description = "步骤序号")
	private Integer stepNo;

	@Schema(description = "路由计划步骤标识")
	private String routeStepId;

	@Schema(description = "协作智能体ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long collaboratorAgentId;

	@Schema(description = "协作任务")
	private String task;

	@Schema(description = "调用原因")
	private String reason;

	@Schema(description = "期望输出")
	private String expectedOutput;

	@Schema(description = "步骤状态")
	private String status;

	@Schema(description = "协作回答")
	private String answer;

	@Schema(description = "回答依据")
	private String evidence;

	@Schema(description = "置信度")
	private String confidence;

	@Schema(description = "错误编码")
	private String errorCode;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "子会话ID")
	private String childThreadId;

	@Schema(description = "子运行请求ID")
	private String childRuntimeRequestId;

	@Schema(description = "步骤总耗时，单位毫秒")
	private Long durationMs;

	@Schema(description = "ReAct 执行耗时，单位毫秒")
	private Long reactMs;

	@Schema(description = "工具调用次数")
	private Integer toolCount;

	@Schema(description = "工具失败次数")
	private Integer toolFailCount;

	@Schema(description = "开始时间")
	private Instant startedAt;

	@Schema(description = "结束时间")
	private Instant finishedAt;

}
