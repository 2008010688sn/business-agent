/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评估中心默认配置初始化结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "评估中心默认配置初始化结果")
public class EvalBootstrapResult {

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估策略ID")
	private Long policyId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估对象ID")
	private Long subjectId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估集ID")
	private Long suiteId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估用例ID")
	private Long caseId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估运行ID")
	private Long runId;

	@Schema(description = "策略初始化状态")
	private EvalBootstrapItemStatus policy;

	@Schema(description = "对象初始化状态")
	private EvalBootstrapItemStatus subject;

	@Schema(description = "评估集初始化状态")
	private EvalBootstrapItemStatus suite;

	@Schema(description = "用例初始化状态")
	private EvalBootstrapItemStatus evalCase;

}
