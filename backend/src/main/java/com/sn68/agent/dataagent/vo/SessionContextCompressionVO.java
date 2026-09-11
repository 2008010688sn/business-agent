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
package com.sn68.agent.dataagent.vo;

import com.sn68.agent.dataagent.enums.SessionContextCompressionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话上下文压缩结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话上下文压缩结果")
public class SessionContextCompressionVO {

	@Schema(description = "压缩状态")
	private SessionContextCompressionStatus status;

	@Schema(description = "是否发生了压缩")
	private Boolean compressed;

	@Schema(description = "压缩前运行时Token数")
	private Long beforeRuntimeTokens;

	@Schema(description = "压缩后运行时Token数")
	private Long afterRuntimeTokens;

	@Schema(description = "压缩前运行时消息数")
	private Integer beforeRuntimeMessageCount;

	@Schema(description = "压缩后运行时消息数")
	private Integer afterRuntimeMessageCount;

	@Schema(description = "压缩后的上下文用量")
	private SessionContextUsageVO displayContextUsage;

	@Schema(description = "是否为估算值")
	private Boolean estimated;

	@Schema(description = "提示消息")
	private String message;

}
