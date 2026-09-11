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
 * 数据源PermissionRule实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("datasource_permission_rule")
@Schema(description = "DataAgent数据源数据权限映射")
public class DatasourcePermissionRule extends SuperEntity<Long> {

	public static final String MISSING_POLICY_ALLOW = "ALLOW_ON_MISSING";

	public static final String MISSING_POLICY_DENY = "DENY_ON_MISSING";

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "数据源ID")
	private Long datasourceId;

	@Schema(description = "物理表名")
	private String tableName;

	@Schema(description = "权限字段名")
	private String columnName;

	@Schema(description = "xx-cloud数据权限类型，例如site、company、twoProject")
	private String dataRefType;

	@Schema(description = "Java值类型：String、Long或Integer")
	private String javaType;

	@Schema(description = "可选DataScopeType整数值，空值或0表示跟随用户权限范围")
	private Integer scopeType;

	@Schema(description = "是否参与SQL权限重写")
	private Boolean enabled;

	@Schema(description = "表未配置权限规则时的处理策略")
	private String missingPolicy;

}
