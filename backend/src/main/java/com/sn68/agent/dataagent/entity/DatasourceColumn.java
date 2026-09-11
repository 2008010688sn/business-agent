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
 * 数据源Column实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("datasource_column")
@Schema(description = "DataAgent数据源字段目录")
public class DatasourceColumn extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "数据源ID")
	private Long datasourceId;

	@Schema(description = "物理表名")
	private String tableName;

	@Schema(description = "物理字段名")
	private String columnName;

	@Schema(description = "数据库字段类型")
	private String columnType;

	@Schema(description = "数据库字段注释")
	private String columnComment;

	@Schema(description = "业务展示名称")
	private String businessName;

	@Schema(description = "样例值")
	private String sampleValues;

	@Schema(description = "是否允许智能体选择该字段")
	private Boolean enabled;

}
