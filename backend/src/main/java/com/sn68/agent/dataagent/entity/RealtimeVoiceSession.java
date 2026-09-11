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

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
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
 * 实时语音助手会话。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "realtime_voice_session", autoResultMap = true)
@Schema(description = "实时语音助手会话")
public class RealtimeVoiceSession extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "会话ID")
	private String sessionId;

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "线程ID")
	private String threadId;

	@Schema(description = "配置时间")
	private Long realtimeConfigId;

	@Schema(description = "运行时时间模式字段")
	private String runtimeMode;

	@Schema(description = "模式字段")
	private String mode;

	@Schema(description = "transport字段")
	private String transport;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "对话模型配置ID")
	private Long chatModelConfigId;

	@Schema(description = "模型配置模式字段")
	private Long asrModelConfigId;

	@Schema(description = "模型配置模式字段")
	private Long ttsModelConfigId;

	@Schema(description = "模型配置时间语音模式字段")
	private Long realtimeVoiceModelConfigId;

	@Schema(description = "语音文件字段")
	private Long voiceProfileId;

	@Schema(description = "allowInterrupt字段")
	private Boolean allowInterrupt;

	@Schema(description = "启用状态")
	private Boolean vadEnabled;

	@Schema(description = "currentTurnId字段")
	private String currentTurnId;

	@Schema(description = "lastSequence字段")
	private Long lastSequence;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "WebSocket令牌")
	private String wsToken;

	@Schema(description = "Token")
	private Instant wsTokenExpiresAt;

	@Schema(description = "会话字段")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String sessionOptions;

	@Schema(description = "开始时间")
	private Instant startedAt;

	@Schema(description = "结束时间")
	private Instant endedAt;

	@Schema(description = "编码错误字段")
	private Integer errorCode;

	@Schema(description = "错误信息")
	private String errorMessage;

}
