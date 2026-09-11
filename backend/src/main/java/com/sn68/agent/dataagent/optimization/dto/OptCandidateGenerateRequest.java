/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * LLM 生成自进化候选请求。默认关闭，需显式打开配置。
 */
@Data
@Schema(description = "LLM 生成优化候选请求")
public class OptCandidateGenerateRequest {

	@Schema(description = "优化实验ID")
	private Long experimentId;

	@Schema(description = "目标类型，缺省 PROMPT")
	private String targetType;

	@Schema(description = "对话模型配置ID，缺省用租户激活 CHAT 模型")
	private Long modelConfigId;

}
