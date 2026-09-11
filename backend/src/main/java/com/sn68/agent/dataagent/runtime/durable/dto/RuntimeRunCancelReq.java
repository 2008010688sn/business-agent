/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 取消持久运行时 Run 请求。
 */
@Schema(description = "取消持久运行时 Run 请求")
public record RuntimeRunCancelReq(

		@Schema(description = "取消原因")
		@Size(max = 512, message = "取消原因长度不能超过 512")
		String reason) {
}
