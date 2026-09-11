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

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * IM 消息日志分页查询参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "平台类型")
public class ImMessagePageQuery extends PageRequest {

	@Schema(description = "平台类型")
	private String provider;

	@Schema(description = "连接器编码")
	private String connectorCode;

	@Schema(description = "会话类型")
	private String conversationType;

	@Schema(description = "外部用户ID")
	private String externalUserId;

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "会话ID")
	private Long sessionId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "direction字段")
	private String direction;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "搜索关键字")
	private String keyword;

}
