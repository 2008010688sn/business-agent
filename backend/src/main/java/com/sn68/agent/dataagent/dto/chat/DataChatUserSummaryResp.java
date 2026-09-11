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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据问答按用户维度的使用统计摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话用户统计摘要")
public class DataChatUserSummaryResp {

	@Schema(description = "用户ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long userId;

	@Schema(description = "创建人账号")
	private String createBy;

	@Schema(description = "创建人姓名")
	private String createName;

	@Schema(description = "会话数量")
	private Long sessionCount;

	@Schema(description = "轮次数量")
	private Long turnCount;

	@Schema(description = "失败轮次数量")
	private Long failedTurnCount;

	@Schema(description = "平均耗时（毫秒）")
	private Double averageDurationMs;

}
