/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * 测试指定已发布只读（READ）工具版本的请求。
 */
@Schema(description = "工具版本测试请求")
public record ToolTestReq(
		@Schema(description = "工具版本ID") Long resourceVersionId,
		@Schema(description = "调用参数") Map<String, Object> arguments) {
}
