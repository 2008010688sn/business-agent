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
 * Data会话轮次实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_chat_turn")
@Schema(description = "DataAgent chat turn diagnostics index")
public class DataChatTurn extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "会话ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long sessionId;

	@Schema(description = "线程ID")
	private String threadId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "平台类型")
	private String requestSource;

	@Schema(description = "平台类型")
	private String provider;

	@Schema(description = "连接器编码")
	private String connectorCode;

	@Schema(description = "外部用户ID")
	private String externalConversationId;

	@Schema(description = "外部用户ID")
	private String externalUserId;

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "用户ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long userId;

	@Schema(description = "question字段")
	private String question;

	@Schema(description = "answer字段")
	private String answer;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "开始时间")
	private Instant startedAt;

	@Schema(description = "finishedAt字段")
	private Instant finishedAt;

	@Schema(description = "durationMs字段")
	private Long durationMs;

	@Schema(description = "消息字段")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long answerExplainMessageId;

	@Schema(description = "orchestrationRunId字段")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long orchestrationRunId;

	@Schema(description = "模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "模型名称")
	private String modelName;

	@Schema(description = "提示词Token数")
	private Long promptTokens;

	@Schema(description = "补全Token数")
	private Long completionTokens;

	@Schema(description = "总Token数")
	private Long totalTokens;

	@Schema(description = "工具数量")
	private Integer toolCount;

	@Schema(description = "工具数量")
	private Integer toolFailCount;

	@Schema(description = "数据源来源字段")
	private Boolean hasDatasource;

	@Schema(description = "hasSql字段")
	private Boolean hasSql;

	@Schema(description = "Route target type")
	private String routeTargetType;

	@Schema(description = "Route target id")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long routeTargetId;

	@Schema(description = "Pinned route target version id")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long routeTargetVersionId;

	@Schema(description = "Route Artifact id")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long routeArtifactId;

	@Schema(description = "Route Profile id")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long routeProfileId;

	@Schema(description = "Route decision")
	private String routeDecision;

	@Schema(description = "Route reason code")
	private String routeReasonCode;

	@Schema(description = "Route degrade mode")
	private String routeDegradeMode;

	@Schema(description = "Selected route target count")
	private Integer routeSelectedCount;

	@Schema(description = "Router duration in milliseconds")
	private Long routeDurationMs;

	@Schema(description = "Knowledge execution duration in milliseconds")
	private Long knowledgeDurationMs;

	@Schema(description = "FLOW execution duration in milliseconds")
	private Long flowDurationMs;

	@Schema(description = "ReAct execution duration in milliseconds")
	private Long reactDurationMs;

}
