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

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Data;

/**
 * 数字员工创建请求。
 */
@Data
@Schema(description = "数字员工创建请求")
public class DigitalEmployeeCreateReq {

	@Schema(description = "员工名称", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "员工名称不能为空")
	private String employeeName;

	@Schema(description = "员工唯一编码（租户内唯一；不传则服务端生成）")
	private String employeeCode;

	@Schema(description = "头像文件ID")
	private String avatarFileId;

	@Schema(description = "岗位（position 标签）")
	private String jobTitle;

	@Schema(description = "员工描述")
	private String description;

	@Schema(description = "系统提示词（草稿）")
	private String systemInstruction;

	@Schema(description = "开场白")
	private String greeting;

	@Schema(description = "业务负责人 userId（仅用于通知）")
	private String managerUserId;

	@Schema(description = "审批人 userId（不得与创建人相同）")
	private String approverUserId;

	@Schema(description = "对话模型配置ID")
	private Long modelConfigId;

	@Schema(description = "路由档案ID")
	private Long routeProfileId;

	@Schema(description = "能力来源 DataAgent（可选，Facade 对话经此桥接）")
	private Long sourceAgentId;

	@Schema(description = "自治级别（ASSISTED/AUTONOMOUS，默认 ASSISTED）")
	private String autonomyLevel;

	@Schema(description = "非授权运营配置（预算提醒等）")
	private Map<String, Object> executionPolicy;

	@Schema(description = "岗位模板编码（如 OPS_ANALYST）；空白创建可不传")
	private String templateCode;

}
