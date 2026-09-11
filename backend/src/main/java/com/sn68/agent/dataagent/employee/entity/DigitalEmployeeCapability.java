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

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 数字员工能力绑定表（草稿态，非运行时事实源）。
 * Seal 时冻结进 Release snapshot；运行时不读本表（主文档 4.2 / 坑 18）；
 * skill_version_id 只能绑定已发布 SkillVersion，市场安装（MarketInstall）幂等落点。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("digital_employee_capability")
@Schema(description = "数字员工能力绑定（草稿态）")
public class DigitalEmployeeCapability extends SuperEntity<Long> {

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

	@Schema(description = "绑定的 Skill 版本ID（已发布 SkillVersion）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long skillVersionId;

	@Schema(description = "是否启用")
	private Boolean enabled;

}
