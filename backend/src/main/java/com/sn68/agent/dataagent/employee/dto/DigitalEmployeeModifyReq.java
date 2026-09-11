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
import java.util.Map;
import lombok.Data;

/**
 * 数字员工修改请求（仅草稿/停用态可改；null 字段不更新）。
 */
@Data
@Schema(description = "数字员工修改请求")
public class DigitalEmployeeModifyReq {

	@Schema(description = "员工名称")
	private String employeeName;

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

	@Schema(description = "业务负责人 userId")
	private String managerUserId;

	@Schema(description = "审批人 userId（不得与创建人相同）")
	private String approverUserId;

	@Schema(description = "对话模型配置ID")
	private Long modelConfigId;

	@Schema(description = "路由档案ID")
	private Long routeProfileId;

	@Schema(description = "自治级别")
	private String autonomyLevel;

	@Schema(description = "非授权运营配置（预算提醒等）")
	private Map<String, Object> executionPolicy;

}
