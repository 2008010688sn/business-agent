/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.dto.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * 工具（执行资源）定义：描述 MCP/HTTP 工具的接入方式与参数映射。
 */
@Schema(description = "工具资源定义")
public record ToolResourceDTO(
		@Schema(description = "主键ID") Long id,
		@Schema(description = "资源类型") String resourceType,
		@Schema(description = "资源键") String resourceKey,
		@Schema(description = "资源名称") String resourceName,
		@Schema(description = "MCP服务编码") String serverCode,
		@Schema(description = "服务名称") String serviceName,
		@Schema(description = "服务基础地址") String baseUrl,
		@Schema(description = "工具名称") String toolName,
		@Schema(description = "接口地址") String endpointUrl,
		@Schema(description = "HTTP方法") String httpMethod,
		@Schema(description = "请求头模板") Map<String, Object> headerTemplate,
		@Schema(description = "认证类型") String authType,
		@Schema(description = "凭证引用标识") String credentialRef,
		@Schema(description = "参数映射") Map<String, Object> paramMapping,
		@Schema(description = "请求体模板") Map<String, Object> requestTemplate,
		@Schema(description = "响应字段映射") Map<String, Object> responseMapping,
		@Schema(description = "是否启用") Boolean enabled,
		@Schema(description = "资源状态") String status,
		@Schema(description = "展示顺序") Integer displayOrder,
		@Schema(description = "扩展配置") Map<String, Object> extConfig
) {
}
