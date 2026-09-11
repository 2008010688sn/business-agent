/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 评估中心默认配置初始化请求。
 */
@Data
@Schema(description = "评估中心默认配置初始化请求")
public class EvalBootstrapRequest {

	@Schema(description = "DataAgent ID（与 employeeId 二选一）")
	private Long agentId;

	@Schema(description = "数字员工 ID（与 agentId 二选一）")
	private Long employeeId;

	@Schema(description = "初始化后是否立即运行评估")
	private Boolean runNow;

	@Schema(description = "冒烟用例用户输入")
	private String caseUserInput;

	@Schema(description = "冒烟用例期望输出")
	private String expectedOutput;

}
