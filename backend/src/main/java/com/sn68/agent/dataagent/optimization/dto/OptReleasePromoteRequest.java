/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 数字员工进化二次确认：把已激活 SANDBOX 的 Release 提升到 PRODUCTION。
 */
@Data
@Schema(description = "数字员工进化提升生产请求")
public class OptReleasePromoteRequest {

	@Schema(description = "优化发布记录ID（SANDBOX 激活记录）")
	private Long releaseId;

	@Schema(description = "提升原因")
	private String reason;

	@Schema(description = "审批记录JSON")
	private String approvalJson;

}
