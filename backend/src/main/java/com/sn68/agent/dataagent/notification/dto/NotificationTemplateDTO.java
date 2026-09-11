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
 * 通知模板数据传输对象。
 */
@Schema(description = "通知模板数据传输对象")
public record NotificationTemplateDTO(
		@Schema(description = "主键ID") Long id,
		@Schema(description = "模板编码") String templateCode,
		@Schema(description = "模板名称") String templateName,
		@Schema(description = "连接器编码") String connectorCode,
		@Schema(description = "平台类型") String provider,
		@Schema(description = "类型消息字段") String messageType,
		@Schema(description = "模板字段") String titleTemplate,
		@Schema(description = "内容模板字段") String contentTemplate,
		@Schema(description = "variableSchema字段") Map<String, Object> variableSchema,
		@Schema(description = "模板字段") String platformTemplateId,
		@Schema(description = "级别字段") String riskLevel,
		@Schema(description = "confirmRequired字段") Boolean confirmRequired,
		@Schema(description = "状态") String status,
		@Schema(description = "displayOrder字段") Integer displayOrder
) {
}
