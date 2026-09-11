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
package com.sn68.agent.dataagent.dto.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 会话报告Generate请求。
 */
@Data
@Schema(description = "分析报告生成请求")
public class ChatReportGenerateReq {

	public static final String REPORT_LEVEL_STANDARD = "standard";

	public static final String REPORT_LEVEL_PROFESSIONAL = "professional";

	@NotNull
	@Schema(description = "会话ID")
	private Long sessionId;

	@NotNull
	@Schema(description = "智能体ID")
	private Long agentId;

	@NotBlank
	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "报告级别：standard 标准确定性模板（默认）；professional 专业叙事（生成失败自动回落标准）")
	private String reportLevel;

}
