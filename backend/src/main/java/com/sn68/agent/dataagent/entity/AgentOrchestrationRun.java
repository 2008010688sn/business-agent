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
 * Agent编排运行实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_orchestration_run")
@Schema(description = "DataAgent编排运行记录")
public class AgentOrchestrationRun extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "编排智能体ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "会话ID")
	private String threadId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "用户问题")
	private String query;

	@Schema(description = "运行状态")
	private String status;

	@Schema(description = "最终回答")
	private String finalAnswer;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "总路由耗时，单位毫秒")
	private Long routeMs;

	@Schema(description = "协作者总耗时，单位毫秒")
	private Long collaboratorMs;

	@Schema(description = "汇总耗时，单位毫秒")
	private Long summaryMs;

	@Schema(description = "编排总耗时，单位毫秒")
	private Long totalMs;

	@Schema(description = "协作者数量")
	private Integer collaboratorCount;

	@Schema(description = "开始时间")
	private Instant startedAt;

	@Schema(description = "结束时间")
	private Instant finishedAt;

}
