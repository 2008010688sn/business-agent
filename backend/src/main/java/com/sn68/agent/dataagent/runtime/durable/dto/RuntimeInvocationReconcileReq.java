/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 外部调用人工对账请求。
 *
 * @param success 核对下游系统后确认外部副作用是否已生效：true 收敛为 SUCCESS，false 收敛为 FAILED
 * @param comment 对账说明（如核对到的外部单号、核对方式与结论），判定为失败时同时作为错误信息
 */
@Schema(description = "外部调用人工对账请求")
public record RuntimeInvocationReconcileReq(

		@Schema(description = "外部副作用是否已生效", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "对账结论不能为空")
		Boolean success,

		@Schema(description = "对账说明，如核对到的外部单号与结论")
		String comment) {
}
