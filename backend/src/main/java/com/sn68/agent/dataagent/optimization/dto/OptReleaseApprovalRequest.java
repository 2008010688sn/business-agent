/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 发布审批请求。
 */
@Data
@Schema(description = "发布审批请求")
public class OptReleaseApprovalRequest {

	@Schema(description = "候选ID")
	private Long candidateId;

	@Schema(description = "当前生产配置哈希")
	private String currentHash;

	@Schema(description = "审批原因")
	private String reason;

	@Schema(description = "审批记录JSON")
	private String approvalJson;

}
