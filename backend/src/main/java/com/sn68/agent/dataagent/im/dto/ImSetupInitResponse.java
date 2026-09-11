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
package com.sn68.agent.dataagent.im.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * IM 接入向导初始化响应。
 */
@Schema(description = "IMSetupInit响应")
public record ImSetupInitResponse(
		@Schema(description = "接入配置会话ID") String setupId,
		@Schema(description = "平台类型") String provider,
		@Schema(description = "模式字段") String connectMode,
		@Schema(description = "地址") String openUrl,
		@Schema(description = "内容") String qrContent,
		@Schema(description = "expiresAt字段") LocalDateTime expiresAt,
		@Schema(description = "requiredFields字段") List<String> requiredFields
) {
}
