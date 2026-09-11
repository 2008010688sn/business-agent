/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/**
 * 评估用例导入请求。
 *
 * <p>两种取材方式并存，二选一：聊天链路给 {@code sessionId + runtimeRequestId}（从 data_chat_turn 取材），
 * 任务链路给 {@code runtimeRunId}（从 agent_runtime_run 取材）。两者同时给出时以 {@code runtimeRunId} 为准。
 */
@Data
@Schema(description = "评估用例导入请求")
public class EvalCaseImportRequest {

	@Schema(description = "评估集ID")
	private Long suiteId;

	@Schema(description = "会话ID")
	private Long sessionId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "持久运行时运行ID，从数字员工任务运行取材时使用")
	private Long runtimeRunId;

	@Schema(description = "用例名称")
	private String caseName;

	@Schema(description = "期望输出")
	private String expectedOutput;

	@Schema(description = "标签")
	private List<String> tags;

}
