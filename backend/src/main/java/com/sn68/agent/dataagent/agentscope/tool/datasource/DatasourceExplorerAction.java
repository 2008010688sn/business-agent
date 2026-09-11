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
package com.sn68.agent.dataagent.agentscope.tool.datasource;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Arrays;

/**
 * 数据源探索工具支持的动作：列表/查找表、取表结构、取关联表、预览行、综合搜索。
 * 枚举名即工具入参 action 的对外取值（JsonCreator 兼容大小写），不可改动。
 */
public enum DatasourceExplorerAction {

	LIST_TABLES,

	FIND_TABLES,

	GET_TABLE_SCHEMA,

	GET_RELATED_TABLES,

	PREVIEW_ROWS,

	SEARCH;

	@JsonCreator
	public static DatasourceExplorerAction fromValue(String value) {
		if (value == null) {
			return null;
		}
		return Arrays.stream(values())
			.filter(action -> action.name().equalsIgnoreCase(value))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("不支持的数据源探索动作：" + value));
	}

}
