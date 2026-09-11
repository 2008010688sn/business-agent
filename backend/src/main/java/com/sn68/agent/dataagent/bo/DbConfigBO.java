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
package com.sn68.agent.dataagent.bo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 数据库连接配置业务对象。
 */
@Schema(description = "数据库连接配置业务对象")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbConfigBO {

	/**
	 * 数据源归属，仅用于连接池缓存键（见
	 * {@code AbstractDBConnectionPool#generateCacheKey}），不参与建连。
	 */
	@Schema(description = "数据源 ID")
	private Long datasourceId;

	@Schema(description = "租户 ID")
	private String tenantId;

	@Schema(description = "数据库 Schema")
	private String schema;

	@Schema(description = "JDBC 连接地址")
	private String url;

	@Schema(description = "数据库用户名")
	private String username;

	@Schema(description = "数据库密码")
	@ToString.Exclude
	private String password;

	@Schema(description = "数据源连接类型")
	private String connectionType;

	@Schema(description = "SQL 方言类型")
	private String dialectType;

}
