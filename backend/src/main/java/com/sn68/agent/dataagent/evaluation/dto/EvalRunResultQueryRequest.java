/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 评测运行结果查询请求。
 */
@Schema(description = "评测运行结果查询请求")
public record EvalRunResultQueryRequest(@Schema(description = "runId字段") Long runId) {
}
