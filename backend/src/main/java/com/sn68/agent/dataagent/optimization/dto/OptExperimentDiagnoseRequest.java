/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 优化实验Diagnose请求。
 */
@Schema(description = "优化实验Diagnose请求")
public record OptExperimentDiagnoseRequest(@Schema(description = "experimentId字段") Long experimentId) {
}
