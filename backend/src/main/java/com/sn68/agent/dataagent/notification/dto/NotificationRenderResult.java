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
 * 通知Render结果实体。
 */
@Schema(description = "通知Render结果实体")
public record NotificationRenderResult(
		@Schema(description = "标题") String title,
		@Schema(description = "内容") String content,
		@Schema(description = "摘要") String summary,
		@Schema(description = "variables字段") Map<String, Object> variables
) {
}
