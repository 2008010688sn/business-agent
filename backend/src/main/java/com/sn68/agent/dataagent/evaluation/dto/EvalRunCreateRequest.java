/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 评估运行创建请求。
 */
@Data
@Schema(description = "评估运行创建请求")
public class EvalRunCreateRequest {

	@Schema(description = "评估集ID")
	private Long suiteId;

	@Schema(description = "评估对象ID，不传时使用评估集默认对象")
	private Long subjectId;

	@Schema(description = "评估策略ID，不传时使用评估集策略或默认策略")
	private Long policyId;

	@Schema(description = "执行意图：LIVE（默认）或 DRY_RUN（离线干跑，禁写、禁外部副作用；自进化候选离线评估必须使用）")
	private String executionIntent;

	@Schema(description = "评估模式：REPLAY（回放历史，默认）或 INVOKE（SANDBOX 真跑）；进化门禁必须 INVOKE")
	private String evalMode;

	@Schema(description = "候选 overlay JSON（systemInstruction/prompt），仅 INVOKE 时覆盖系统提示")
	private String candidateOverlayJson;

}
