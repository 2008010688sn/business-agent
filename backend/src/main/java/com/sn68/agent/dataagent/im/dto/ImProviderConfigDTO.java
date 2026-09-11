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
import java.util.Map;

/**
 * IM 平台配置。
 */
@Schema(description = "IM平台配置数据传输对象")
public record ImProviderConfigDTO(
		@Schema(description = "主键ID") Long id,
		@Schema(description = "平台类型") String provider,
		@Schema(description = "配置") Map<String, Object> config,
		@Schema(description = "状态") String status,
		@Schema(description = "displayOrder字段") Integer displayOrder
) {
}
