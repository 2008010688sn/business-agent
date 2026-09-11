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
import lombok.Builder;
import lombok.Data;

/**
 * 会话报告Generate响应。
 */
@Data
@Builder
@Schema(description = "分析报告生成结果")
public class ChatReportGenerateResp {

	@Schema(description = "Markdown报告内容")
	private String content;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "实际生效的报告级别：standard/professional")
	private String reportLevel;

	@Schema(description = "降级标记：true 表示专业叙事生成失败已回落标准报告")
	private Boolean degraded;

}
