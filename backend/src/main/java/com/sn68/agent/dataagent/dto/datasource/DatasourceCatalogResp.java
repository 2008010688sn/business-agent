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

import com.sn68.agent.dataagent.entity.DatasourceColumn;
import com.sn68.agent.dataagent.entity.DatasourceTable;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * 数据源目录：已同步的数据表及各表字段清单。
 */
@Data
@Builder
@Schema(description = "数据源目录")
public class DatasourceCatalogResp {

	@Schema(description = "数据源ID")
	private Long datasourceId;

	@Schema(description = "数据表列表")
	private List<DatasourceTable> tables;

	@Schema(description = "各数据表的字段列表")
	private Map<String, List<DatasourceColumn>> columnsByTable;

}
