/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 持久运行时审批决定请求（通过意见可空；驳回意见必填，由服务端校验）。
 */
@Schema(description = "持久运行时审批决定请求")
public record RuntimeApprovalDecisionReq(

		@Schema(description = "审批意见（驳回时必填）")
		String comment) {
}
