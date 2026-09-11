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

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;

/**
 * Data会话用户Summary查询请求。
 */
@Data
@Schema(description = "DataAgent chat diagnostics user summary query")
public class DataChatUserSummaryQueryReq {

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "开始时间")
	private Instant startTime;

	@Schema(description = "结束时间")
	private Instant endTime;

}
