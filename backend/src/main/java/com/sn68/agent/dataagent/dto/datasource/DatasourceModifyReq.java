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
 * 修改数据源请求。
 *
 * <p>与 {@link DatasourceCreateReq} 同样不接受 {@code connectionUrl}：连接串每次保存都由服务端按
 * {@code host}/{@code port}/{@code databaseName} 重新生成（PRD S-2）。因此连接三要素在修改时同样必填，
 * 缺一就无法确定要生成哪条连接串。
 *
 * <p>{@code username} / {@code password} 留空或回填掩码串表示「沿用库内已有凭据」，
 * 由 {@code SensitiveConfigCryptoService#shouldKeepExisting} 判定，避免前端把掩码写回、抹掉真实凭据。
 */
@Schema(description = "修改数据源请求")
public record DatasourceModifyReq(
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

		@Schema(description = "用户名，留空或回填掩码串表示沿用已有账号")
		@Size(max = 255, message = "用户名长度不能超过255") String username,

		@Schema(description = "密码，留空或回填掩码串表示沿用已有密码") String password,

		@Schema(description = "状态") @Size(max = 50, message = "状态长度不能超过50") String status,

		@Schema(description = "描述") String description
) {
}
