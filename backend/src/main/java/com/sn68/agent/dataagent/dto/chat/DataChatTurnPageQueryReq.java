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
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据问答会话轮次（诊断视角）分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "会话轮次诊断分页查询")
public class DataChatTurnPageQueryReq extends PageRequest {

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "用户ID")
	private Long userId;

	@Schema(description = "会话ID")
	private Long sessionId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "搜索关键字")
	private String keyword;

	@Schema(description = "轮次状态")
	private String status;

	@Schema(description = "请求来源")
	private String requestSource;

	@Schema(description = "模型提供商")
	private String provider;

	@Schema(description = "连接器编码")
	private String connectorCode;

	@Schema(description = "外部用户ID")
	private String externalUserId;

	@Schema(description = "是否使用了数据源")
	private Boolean hasDatasource;

	@Schema(description = "是否生成了SQL")
	private Boolean hasSql;

	@Schema(description = "是否发生了工具调用")
	private Boolean hasToolCall;

	@Schema(description = "开始时间")
	private Instant startTime;

	@Schema(description = "结束时间")
	private Instant endTime;

}
