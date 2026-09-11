/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 候选评估请求。
 */
@Data
@Schema(description = "候选评估请求")
public class OptCandidateEvalRequest {

	@Schema(description = "候选ID")
	private Long candidateId;

	@Schema(description = "沙箱评估运行ID")
	private Long sandboxRunId;

}
