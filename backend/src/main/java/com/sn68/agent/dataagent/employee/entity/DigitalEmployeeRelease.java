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
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 数字员工发布表（DRAFT → SEALED → PUBLISHED → RETIRED）。
 * Seal 时冻结能力清单进 snapshot（运行时唯一能力来源），Seal 后草稿改动不影响本表；
 * authorization_policy_version_id/hash 为发布门禁预留（运行时策略指针以授权绑定表为准，主文档 5.7）。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "digital_employee_release", autoResultMap = true)
@Schema(description = "数字员工发布")
public class DigitalEmployeeRelease extends SuperEntity<Long> {

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

	@Schema(description = "发布序号，员工内单调递增")
	private Integer releaseNo;

	@Schema(description = "快照 schema 版本")
	private String schemaVersion;

	@TableField(typeHandler = JsonbStringTypeHandler.class)
	@Schema(description = "完整运行规范快照 JSON（Seal 冻结，运行时唯一能力来源）")
	private String snapshot;

	@Schema(description = "快照 SHA-256（Seal 时计算）")
	private String specHash;

	@Schema(description = "基于哪个历史 Release 创建（可空）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long baseReleaseId;

	@Schema(description = "来源类型（DRAFT/BASE_RELEASE 等）")
	private String sourceType;

	@Schema(description = "来源 DataAgent（可空）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long sourceAgentId;

	@Schema(description = "来源 DataAgent Release（可空）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long sourceReleaseId;

	@Schema(description = "状态：DRAFT/SEALED/PUBLISHED/RETIRED")
	private String status;

	@Schema(description = "封版时间")
	private Instant sealedAt;

	@Schema(description = "封版人")
	private String sealedBy;

	@Schema(description = "发布时间")
	private Instant publishedAt;

	@Schema(description = "发布人")
	private String publishedBy;

	@Schema(description = "发布门禁记录的策略版本ID（预留可空）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long authorizationPolicyVersionId;

	@Schema(description = "策略 JSON checksum（预留可空）")
	private String authorizationPolicyHash;

}
