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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 数字员工身份表（主文档 4.1/4.2）。
 * 员工不写 t_user，执行身份为 IAM Service Principal（sp_ 前缀）；租户防线为显式 tenant_id 谓词。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "digital_employee", autoResultMap = true)
@Schema(description = "数字员工身份")
public class DigitalEmployee extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID（String 基线）")
	private String tenantId;

	@Schema(description = "员工唯一编码（租户内唯一）")
	private String employeeCode;

	@Schema(description = "员工名称")
	private String employeeName;

	@Schema(description = "头像文件ID")
	private String avatarFileId;

	@Schema(description = "岗位（position 标签，仅展示用）")
	private String jobTitle;

	@Schema(description = "员工描述")
	private String description;

	@Schema(description = "系统提示词（草稿）")
	private String systemInstruction;

	@Schema(description = "开场白")
	private String greeting;

	@Schema(description = "状态：DRAFT/ENABLED/DISABLED/ARCHIVED")
	private String status;

	@Schema(description = "业务负责人（仅用于通知，不参与授权）")
	private String managerUserId;

	@Schema(description = "审批人（真人 userId；不得与创建人相同）")
	private String approverUserId;

	@Schema(description = "IAM Service Principal ID（sp_ 前缀，可空）")
	private String iamPrincipalId;

	@Schema(description = "Principal 开通状态：PENDING/READY/FAILED/DISABLED")
	private String principalStatus;

	@Schema(description = "最近一次观测到的 auth_revision（本地缓存比对基准）")
	private Long principalRevision;

	@Schema(description = "对话模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "路由档案ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long routeProfileId;

	@Schema(description = "能力来源 DataAgent（Facade 对话经此桥接既有运行时）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long sourceAgentId;

	@Schema(description = "自治级别（ASSISTED 等，运营配置）")
	private String autonomyLevel;

	@TableField(typeHandler = JsonbStringTypeHandler.class)
	@Schema(description = "非授权运营配置 JSON（预算提醒等；授权事实源在授权中心）")
	private String executionPolicy;

	@Schema(description = "草稿修订号（草稿每次变更 +1）")
	private Integer draftRevision;

	@Schema(description = "CAS 版本号（启用/停用等状态迁移防覆盖）")
	private Long stateVersion;

}
