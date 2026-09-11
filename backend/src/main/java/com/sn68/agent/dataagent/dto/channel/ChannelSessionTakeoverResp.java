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

import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.entity.DataChatSession;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Channel会话Takeover响应。
 */
@Schema(description = "渠道会话接管结果")
public record ChannelSessionTakeoverResp(
		@Schema(description = "内部会话") DataChatSession session,
		@Schema(description = "历史消息") List<DataChatMessage> messages) {
}
