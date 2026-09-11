/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import com.sn68.agent.dataagent.optimization.entity.DataAgentOptCandidate;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptCandidateEval;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptExperiment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 优化实验详情。
 */
@Builder
@Schema(description = "优化实验详情")
public record OptExperimentDetailDTO(

		@Schema(description = "优化实验") DataAgentOptExperiment experiment,

		@Schema(description = "候选版本列表") List<DataAgentOptCandidate> candidates,

		@Schema(description = "候选最新评估列表") List<DataAgentOptCandidateEval> latestCandidateEvaluations) {
}
