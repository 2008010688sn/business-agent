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
package com.sn68.agent.dataagent.employee.dto;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;

/**
 * 数字员工档案响应（接口契约；雪花 ID 序列化为字符串，避免前端精度丢失）。
 */
@Data
@Schema(description = "数字员工档案响应")
public class DigitalEmployeeResp {

	@Schema(description = "员工ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "员工唯一编码")
	private String employeeCode;

	@Schema(description = "员工名称")
	private String employeeName;

	@Schema(description = "头像文件ID")
	private String avatarFileId;

	@Schema(description = "岗位")
	private String jobTitle;

	@Schema(description = "员工描述")
	private String description;

	@Schema(description = "系统提示词（草稿）")
	private String systemInstruction;

	@Schema(description = "开场白")
	private String greeting;

	@Schema(description = "状态：DRAFT/ENABLED/DISABLED/ARCHIVED")
	private String status;

	@Schema(description = "业务负责人 userId")
	private String managerUserId;

	@Schema(description = "审批人 userId")
	private String approverUserId;

	@Schema(description = "IAM Service Principal ID（sp_ 前缀，可空）")
	private String iamPrincipalId;

	@Schema(description = "Principal 开通状态：PENDING/READY/FAILED/DISABLED")
	private String principalStatus;

	@Schema(description = "最近一次观测到的 auth_revision")
	private Long principalRevision;

	@Schema(description = "对话模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "路由档案ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long routeProfileId;

	@Schema(description = "能力来源 DataAgent ID（可空；运行时事实源是员工 Release 快照）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long sourceAgentId;

	@Schema(description = "当前员工配置的模型数量")
	private Long modelCount;

	@Schema(description = "当前员工绑定的能力数量（含停用绑定）")
	private Long capabilityCount;

	@Schema(description = "自治级别")
	private String autonomyLevel;

	@Schema(description = "非授权运营配置 JSON")
	private String executionPolicy;

	@Schema(description = "创建时选用的岗位模板编码（来自 executionPolicy.jobTemplateCode）")
	private String jobTemplateCode;

	@Schema(description = "草稿修订号")
	private Integer draftRevision;

	@Schema(description = "CAS 版本号")
	private Long stateVersion;

	@Schema(description = "灰度开关 spring.ai.agent.digital-employee.rollout.enabled；关闭时不可 PRINCIPAL 执行")
	private Boolean rolloutEnabled;

	@Schema(description = "创建人")
	private String createBy;

	@Schema(description = "创建人名称")
	private String createName;

	@Schema(description = "创建时间")
	private Instant createTime;

	@Schema(description = "最后修改时间")
	private Instant lastModifyTime;

	@Schema(description = "最后修改人")
	private String lastModifyBy;

	@Schema(description = "最后修改人名称")
	private String lastModifyName;

	/**
	 * 实体转响应；rolloutEnabled 来自运行配置，不是表字段。
	 */
	public static DigitalEmployeeResp from(DigitalEmployee employee, boolean rolloutEnabled) {
		if (employee == null) {
			return null;
		}
		DigitalEmployeeResp resp = BeanUtil.copyProperties(employee, DigitalEmployeeResp.class);
		resp.setRolloutEnabled(rolloutEnabled);
		return resp;
	}

}
