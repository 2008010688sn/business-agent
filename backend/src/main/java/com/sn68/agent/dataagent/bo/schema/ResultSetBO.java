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
package com.sn68.agent.dataagent.bo.schema;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SQL 查询结果集业务对象，承载列名、数据行与错误信息。
 */
@Schema(description = "SQL 查询结果集业务对象")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class ResultSetBO implements Cloneable {

	@Schema(description = "结果列名列表")
	private List<String> column;

	@Schema(description = "结果数据行")
	private List<Map<String, String>> data;

	@Schema(description = "查询错误信息")
	private String errorMsg;

	@Override
	public ResultSetBO clone() {
		return ResultSetBO.builder()
			.column(new ArrayList<>(this.column))
			.data(this.data.stream().map(HashMap::new).collect(Collectors.toList()))
			.build();
	}

}
