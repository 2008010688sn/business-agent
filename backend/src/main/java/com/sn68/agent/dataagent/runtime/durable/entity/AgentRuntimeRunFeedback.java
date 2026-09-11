/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.entity;

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
 * 运行人工反馈。同一用户对同一 Run 一条记录，重复提交覆盖。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_runtime_run_feedback")
@Schema(description = "运行人工反馈")
public class AgentRuntimeRunFeedback extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "运行ID")
	private Long runId;

	@Schema(description = "评价人 userId")
	private String userId;

	@Schema(description = "UP / DOWN")
	private String rating;

	@Schema(description = "评语")
	private String comment;

}
