/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 回滚记录请求。
 */
@Data
@Schema(description = "回滚记录请求")
public class OptRollbackRequest {

	@Schema(description = "发布记录ID")
	private Long releaseId;

	@Schema(description = "回滚原因")
	private String reason;

	@Schema(description = "审批记录JSON")
	private String approvalJson;

}
