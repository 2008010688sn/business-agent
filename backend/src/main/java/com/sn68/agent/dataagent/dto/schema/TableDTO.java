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
package com.sn68.agent.dataagent.dto.schema;

import com.sn68.agent.dataagent.util.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据表结构描述：字段列表与主键信息。
 */
@Data
@NoArgsConstructor
@Schema(description = "数据表结构")
public class TableDTO {

	@Schema(description = "表名")
	private String name;

	@Schema(description = "表描述")
	private String description;

	@Schema(description = "字段列表")
	private List<ColumnDTO> column = new ArrayList<ColumnDTO>();

	@Schema(description = "主键字段列表")
	private List<String> primaryKeys;

	@Override
	public String toString() {
		ObjectMapper objectMapper = JsonUtil.getObjectMapper();
		try {
			return objectMapper.writeValueAsString(this);
		}
		catch (JsonProcessingException e) {
			// 自身结构序列化失败属基础设施/编程错误，500 才是正确结果。
			throw new IllegalStateException("Failed to convert object to JSON string", e);
		}
	}

}
