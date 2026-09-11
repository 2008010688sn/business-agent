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
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 数据源Table实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("datasource_table")
@Schema(description = "DataAgent数据源表目录")
public class DatasourceTable extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "数据源ID")
	private Long datasourceId;

	@Schema(description = "物理表名")
	private String tableName;

	@Schema(description = "数据库表注释")
	private String tableComment;

	@Schema(description = "业务展示名称")
	private String businessName;

	@Schema(description = "是否允许智能体选择该表")
	private Boolean enabled;

	public DatasourceTable(Long datasourceId, String tableName, String tableComment, Boolean enabled) {
		this.datasourceId = datasourceId;
		this.tableName = tableName;
		this.tableComment = tableComment;
		this.enabled = enabled;
	}

}
