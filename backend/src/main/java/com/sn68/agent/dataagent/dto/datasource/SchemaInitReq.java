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

import com.sn68.agent.dataagent.bo.DbConfigBO;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Schema 初始化请求：按数据库连接配置向量化指定 Skill 的数据表结构。
 */
@Data
@Schema(description = "SchemaInit请求")
public class SchemaInitReq implements Serializable {

	@Schema(description = "数据库连接配置")
	private DbConfigBO dbConfig;

	@Schema(description = "Skill ID")
	private Long skillId;

	@Schema(description = "需要初始化的数据表列表")
	private List<String> tables;

	@Schema(description = "各数据表的可见字段白名单")
	private Map<String, List<String>> visibleColumnsByTable;

}
