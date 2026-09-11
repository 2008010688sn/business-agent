/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 评估用例保存请求。
 */
@Data
@Schema(description = "评估用例保存请求")
public class EvalCaseRequest {

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

	@Schema(description = "代码 oracle JSON：expectedStdout 或 expectedScript")
	private String codeOracleJson;

	@Schema(description = "用例状态")
	private String status;

}
