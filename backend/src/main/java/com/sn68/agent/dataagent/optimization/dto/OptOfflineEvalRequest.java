/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 自进化 LOOP 候选离线评估发起请求。执行意图由服务端强制为 DRY_RUN，调用方不可指定。
 */
@Data
@Schema(description = "候选离线评估发起请求")
public class OptOfflineEvalRequest {

	@Schema(description = "候选ID")
	private Long candidateId;

	@Schema(description = "评估集ID（离线评估用例集）")
	private Long suiteId;

	@Schema(description = "评估策略ID，不传时使用评估集策略或默认策略")
	private Long policyId;

}
