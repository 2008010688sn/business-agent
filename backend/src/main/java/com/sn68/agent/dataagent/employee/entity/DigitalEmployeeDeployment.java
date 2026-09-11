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
package com.sn68.agent.dataagent.employee.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.framework.commons.entity.Entity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 数字员工部署表（CAS 激活/回滚）。
 * 按主文档 4.2 约定不做软删除（审计走 deployment_version 递增链路），
 * 故直接继承 {@link Entity}（而非 SuperEntity），避免 @TableLogic 自动追加 deleted 谓词；
 * lastModify* 字段由本类显式声明以复用框架 updateFill 自动填充。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("digital_employee_deployment")
@Schema(description = "数字员工部署")
public class DigitalEmployeeDeployment extends Entity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID（String 基线）")
	private String tenantId;

	@Schema(description = "数字员工ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long employeeId;

	@Schema(description = "环境：SANDBOX/PRODUCTION")
	private String environment;

	@Schema(description = "当前激活的 digital_employee_release.id")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long activeReleaseId;

	@Schema(description = "切换前的激活 Release（回滚参照）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long previousReleaseId;

	@Schema(description = "CAS 版本号，每次激活/回滚 +1；UPDATE 影响 0 行即版本冲突")
	private Integer deploymentVersion;

	@Schema(description = "状态：INACTIVE/ACTIVE/ROLLING_BACK")
	private String status;

	@Schema(description = "最近操作人")
	private String deployedBy;

	@Schema(description = "最近操作时间")
	private Instant deployedAt;

	@Schema(description = "最后修改时间")
	@TableField(value = "last_modify_time")
	private Instant lastModifyTime;

	@Schema(description = "最后修改人ID")
	@TableField(value = "last_modify_by")
	private String lastModifyBy;

	@Schema(description = "最后修改人名称")
	@TableField(value = "last_modify_name")
	private String lastModifyName;

}
