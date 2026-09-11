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
import lombok.Data;

/**
 * 数字员工对话请求（Facade 接缝：客户端只传员工ID + 输入；身份与租户由服务端从登录态解析）。
 */
@Data
@Schema(description = "数字员工对话请求")
public class EmployeeConversationReq {

	@Schema(description = "用户输入", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "输入内容不能为空")
	private String query;

	@Schema(description = "会话ID（不传则新建会话；会话须归属于当前用户与该员工）")
	private Long sessionId;

	@Schema(description = "对话选用的 CHAT 模型配置ID（须在员工可用列表内；空则用默认）")
	private Long chatModelConfigId;

}
