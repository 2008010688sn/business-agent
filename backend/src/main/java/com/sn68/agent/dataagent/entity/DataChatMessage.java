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
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;

/**
 * Data会话消息实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_chat_message")
@Schema(description = "DataAgent会话消息")
public class DataChatMessage extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "会话ID")
	private Long sessionId;

	@Schema(description = "消息角色")
	private String role;

	@Schema(description = "消息内容")
	private String content;

	@Schema(description = "消息类型")
	private String messageType;

	@Schema(description = "元数据JSON")
	private String metadata;

}
