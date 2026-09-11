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
package com.sn68.agent.dataagent.dto.tts;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实时语音信令交换响应（WebRTC answer 与网关信息）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "实时语音信令响应")
public class RealtimeSignalResp {

	@Schema(description = "信令处理状态")
	private String status;

	@Schema(description = "会话ID")
	private String sessionId;

	@Schema(description = "信令类型（offer/answer/candidate等）")
	private String type;

	@Schema(description = "SDP会话描述")
	private String sdp;

	@Schema(description = "语音网关地址")
	private String gatewayUrl;

	@Schema(description = "提示消息")
	private String message;

	@Schema(description = "扩展数据")
	@Builder.Default
	private Map<String, Object> data = new LinkedHashMap<>();

}
