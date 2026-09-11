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
package com.sn68.agent.dataagent.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * 通知目标数据传输对象。
 */
@Schema(description = "通知目标数据传输对象")
public record NotificationTargetDTO(
		@Schema(description = "主键ID") Long id,
		@Schema(description = "目标别名") String targetAlias,
		@Schema(description = "目标名称") String targetName,
		@Schema(description = "目标类型") String targetType,
		@Schema(description = "连接器编码") String connectorCode,
		@Schema(description = "平台类型") String provider,
		@Schema(description = "配置目标字段") Map<String, Object> targetConfig,
		@Schema(description = "编码") String resolverCode,
		@Schema(description = "状态") String status,
		@Schema(description = "displayOrder字段") Integer displayOrder
) {
}
