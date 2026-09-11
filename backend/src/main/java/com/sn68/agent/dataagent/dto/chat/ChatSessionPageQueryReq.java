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

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话会话分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "DataAgent 会话分页查询参数")
public class ChatSessionPageQueryReq extends PageRequest {

	@Schema(description = "智能体ID")
	@NotNull(message = "agentId不能为空")
	private Long agentId;

	@Schema(description = "会话渠道类型")
	private String channelType;

	@Schema(description = "外部渠道平台")
	private String provider;

	@Schema(description = "渠道连接器编码")
	private String connectorCode;

	@Schema(description = "外部用户ID")
	private String externalUserId;

	public ChatSessionPageQueryReq() {
		setSize(10);
	}

}
