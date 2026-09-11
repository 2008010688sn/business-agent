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
package com.sn68.agent.dataagent.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Data;

/**
 * Agent任务定义创建请求（create 与 modify 不共用 DTO）。
 */
@Data
@Schema(description = "Agent任务定义创建请求")
public class AgentTaskDefinitionSaveReq {

	@NotNull(message = "digitalEmployeeId 不能为空")
	@Schema(description = "所属数字员工 ID")
	private Long digitalEmployeeId;
	
	@NotNull(message = "employeeReleaseId 不能为空")
	@Schema(description = "绑定的数字员工发布版本 ID")
	private Long employeeReleaseId;

	@NotBlank(message = "任务名称不能为空")
	@Size(max = 128, message = "任务名称不能超过128字符")
	@Schema(description = "任务名称")
	private String taskName;

	@Size(max = 1024, message = "任务描述不能超过1024字符")
	@Schema(description = "任务描述")
	private String taskDescription;

	@Schema(description = "任务类型（业务分类，如 REPORT/MONITOR/OPERATION）")
	private String taskType;

	@Schema(description = "默认自治级别（AUTONOMOUS/ASSISTED，缺省 ASSISTED；高风险写任务强制 ASSISTED）")
	private String defaultAutonomyLevel;

	@Schema(description = "是否高风险写任务")
	private Boolean highRiskWrite;

	@Schema(description = "受限执行主体（已废弃，服务端从数字员工 iamPrincipalId 写入，忽略客户端传入）")
	private String servicePrincipal;

	@Schema(description = "任务参数快照（随首个版本落库）")
	private Map<String, Object> paramsSnapshot;

	@Schema(description = "提示词快照（随首个版本落库）")
	private Map<String, Object> promptSnapshot;

}
