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
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据源响应。
 *
 * <p>字段名与顺序沿用改造前直接返回 {@code Datasource} 实体时的序列化结果，客户端契约不变。
 * <b>刻意缺失的三个字段</b>：{@code tenantId}、{@code password}、{@code connectionUrl} —— 完整 JDBC 串
 * 加账号等于内网库拓扑与账号枚举（PRD P0-10），改由 {@code passwordConfigured} /
 * {@code connectionUrlConfigured} 两个布尔位表达「是否已配置」，{@code username} 走掩码。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "数据源响应")
public class DatasourceResp {

	@Schema(description = "ID")
	private Long id;

	@Schema(description = "创建人ID")
	private String createBy;

	@Schema(description = "创建人名称")
	private String createName;

	@Schema(description = "创建时间")
	private Instant createTime;

	@Schema(description = "最后修改时间")
	private Instant lastModifyTime;

	@Schema(description = "最后修改人ID")
	private String lastModifyBy;

	@Schema(description = "最后修改人名称")
	private String lastModifyName;

	@Schema(description = "逻辑删除")
	private Boolean deleted;

	@Schema(description = "数据源名称")
	private String name;

	@Schema(description = "数据源类型")
	private String type;

	@Schema(description = "主机地址")
	private String host;

	@Schema(description = "端口")
	private Integer port;

	@Schema(description = "数据库名称")
	private String databaseName;

	@Schema(description = "用户名（掩码）")
	private String username;

	@Schema(description = "密码是否已配置")
	private Boolean passwordConfigured;

	@Schema(description = "连接URL是否已配置")
	private Boolean connectionUrlConfigured;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "连接测试状态")
	private String testStatus;

	@Schema(description = "描述")
	private String description;

	@Schema(description = "创建人ID")
	private Long creatorId;

}
