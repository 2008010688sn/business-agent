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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话Attachment数据传输对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "聊天附件")
public class ChatAttachmentDTO {

	@Schema(description = "附件类型")
	private String type;

	@Schema(description = "存储键")
	private String storageKey;

	@Schema(description = "访问地址")
	private String url;

	@Schema(description = "预览地址")
	private String previewUrl;

	@Schema(description = "内容类型")
	private String contentType;

	@Schema(description = "文件名称")
	private String fileName;

	@Schema(description = "文件大小")
	private Long size;

	@Schema(description = "Base64内容，仅用于当轮模型输入")
	private String data;

}
