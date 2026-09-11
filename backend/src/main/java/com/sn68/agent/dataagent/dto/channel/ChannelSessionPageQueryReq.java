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
package com.sn68.agent.dataagent.dto.channel;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Channel会话分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "渠道会话分页查询参数")
public class ChannelSessionPageQueryReq extends PageRequest {

	@Schema(description = "智能体ID")
	private Long agentId;

	@Schema(description = "内部会话ID")
	private Long sessionId;

	@Schema(description = "外部渠道平台")
	private String provider;

	@Schema(description = "渠道连接器编码")
	private String connectorCode;

	@Schema(description = "外部用户ID")
	private String externalUserId;

	@Schema(description = "状态")
	private String status;

	public ChannelSessionPageQueryReq() {
		setSize(10);
	}

}
