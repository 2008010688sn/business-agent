/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * AgentToken用量实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_token_usage")
@Schema(description = "DataAgent Token 用量流水")
public class AgentTokenUsage extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "租户编码")
	private String tenantCode;

	@Schema(description = "用户ID")
	private String userId;

	@Schema(description = "名称")
	private String userNickName;

	@Schema(description = "客户端ID")
	private String clientId;

	@Schema(description = "teamIdsJson字段")
	private String teamIdsJson;

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "Agent名称")
	private String agentName;

	@Schema(description = "会话ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long sessionId;

	@Schema(description = "线程ID")
	private String threadId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "运行时请求时间")
	private String rootRuntimeRequestId;

	@Schema(description = "运行时请求时间")
	private String parentRuntimeRequestId;

	@Schema(description = "orchestrationRunId字段")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long orchestrationRunId;

	@Schema(description = "orchestrationStepId字段")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long orchestrationStepId;

	@Schema(description = "模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "平台类型")
	private String provider;

	@Schema(description = "模型名称")
	private String modelName;

	@Schema(description = "用量来源")
	private String modelType;

	@Schema(description = "用量来源")
	private String usageSource;

	@Schema(description = "提示词Token数")
	private Long promptTokens;

	@Schema(description = "补全Token数")
	private Long completionTokens;

	@Schema(description = "总Token数")
	private Long totalTokens;

	@Schema(description = "模式字段")
	private String meteringMode;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "durationMs字段")
	private Long durationMs;

	@Schema(description = "编码错误字段")
	private String errorCode;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "请求来源字段")
	private String requestSource;

	@Schema(description = "cacheHit字段")
	private Boolean cacheHit;

	@Schema(description = "用量字段")
	private String rawUsageJson;

}
