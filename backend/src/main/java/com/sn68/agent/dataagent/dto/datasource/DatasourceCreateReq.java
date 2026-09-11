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
package com.sn68.agent.dataagent.dto.datasource;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建数据源请求。
 *
 * <p><b>刻意不提供 {@code connectionUrl}</b>：JDBC 连接串由服务端按 {@code host}/{@code port}/
 * {@code databaseName} 组装，调用方传入的原始连接串等于把 {@code autoDeserialize}、
 * {@code allowLoadLocalInfile}、{@code socketFactory} 等驱动参数直接交到外部手里（PRD S-2）。
 * 未知属性全局忽略，因此老客户端继续携带该字段也只会被丢弃，不会生效。
 */
@Schema(description = "创建数据源请求")
public record DatasourceCreateReq(
		@Schema(description = "数据源名称") @NotBlank(message = "数据源名称不能为空")
		@Size(max = 255, message = "数据源名称长度不能超过255") String name,

		@Schema(description = "数据源类型") @NotBlank(message = "数据源类型不能为空")
		@Size(max = 50, message = "数据源类型长度不能超过50") String type,

		@Schema(description = "主机地址") @NotBlank(message = "主机地址不能为空")
		@Size(max = 255, message = "主机地址长度不能超过255") String host,

		@Schema(description = "端口") @NotNull(message = "端口不能为空")
		@Min(value = 1, message = "端口必须在1-65535之间")
		@Max(value = 65535, message = "端口必须在1-65535之间") Integer port,

		@Schema(description = "数据库名称") @NotBlank(message = "数据库名称不能为空")
		@Size(max = 255, message = "数据库名称长度不能超过255") String databaseName,

		@Schema(description = "用户名") @Size(max = 255, message = "用户名长度不能超过255") String username,

		@Schema(description = "密码") String password,

		@Schema(description = "状态") @Size(max = 50, message = "状态长度不能超过50") String status,

		@Schema(description = "描述") String description
) {
}
