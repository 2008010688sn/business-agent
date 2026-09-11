/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.entity;

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
 * DataAgent 评估用例。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_eval_case")
@Schema(description = "DataAgent 评估用例")
public class DataAgentEvalCase extends SuperEntity<Long> {

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

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估集ID")
	private Long suiteId;

	@Schema(description = "用例名称")
	private String caseName;

	@Schema(description = "用户输入")
	private String userInput;

	@Schema(description = "期望输出")
	private String expectedOutput;

	@Schema(description = "安全约束JSON")
	private String safetyConstraintsJson;

	@Schema(description = "效率预算JSON")
	private String efficiencyBudgetJson;

	@Schema(description = "标签JSON")
	private String tagsJson;

	@Schema(description = "Trace 摘要快照JSON")
	private String traceSnapshotJson;

	@Schema(description = "代码 oracle JSON：expectedStdout 或 expectedScript，在 Docker 池中执行比对")
	private String codeOracleJson;

	@Schema(description = "用例状态")
	private String status;

}
