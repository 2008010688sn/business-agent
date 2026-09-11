/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 发布不可变工具（执行资源）版本的请求。
 */
@Schema(description = "工具版本发布请求")
public record ToolVersionPublishReq(
		@Schema(description = "访问模式") String accessMode,
		@Schema(description = "暴露模式") String exposureMode,
		@Schema(description = "权限编码") String permissionCode,
		@Schema(description = "是否需要人工确认") Boolean confirmRequired,
		@Schema(description = "是否要求幂等") Boolean idempotencyRequired,
		@Schema(description = "调用超时（毫秒）") Integer timeoutMs,
		@Schema(description = "输入Schema定义") Map<String, Object> inputSchema,
		@Schema(description = "输出Schema定义") Map<String, Object> outputSchema,
		@Schema(description = "运行时参数映射") Map<String, String> runtimeParamMappings,
		@Schema(description = "响应字段映射") Map<String, Object> responseMappings,
		@Schema(description = "敏感字段列表") List<String> sensitiveFields) {
}
