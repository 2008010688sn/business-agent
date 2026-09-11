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
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * AgentToken用量Day实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_token_usage_day")
@Schema(description = "DataAgent Token 日聚合")
public class AgentTokenUsageDay extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "统计日期")
	private LocalDate statDate;

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "用户ID")
	private String userId;

	@Schema(description = "名称")
	private String userNickName;

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "Agent名称")
	private String agentName;

	@Schema(description = "模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "平台类型")
	private String provider;

	@Schema(description = "模型名称")
	private String modelName;

	@Schema(description = "用量来源")
	private String usageSource;

	@Schema(description = "请求次数")
	private Long requestCount;

	@Schema(description = "成功次数")
	private Long successCount;

	@Schema(description = "失败次数")
	private Long failedCount;

	@Schema(description = "提示词Token数")
	private Long promptTokens;

	@Schema(description = "补全Token数")
	private Long completionTokens;

	@Schema(description = "总Token数")
	private Long totalTokens;

	@Schema(description = "实际Token数")
	private Long actualTokens;

	@Schema(description = "预估Token数")
	private Long estimatedTokens;

	@Schema(description = "未知次数")
	private Long unknownCount;

}
