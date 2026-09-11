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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TTS 声音克隆音源样本信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "TTS音源样本")
public class TtsVoiceSampleResp {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "样本文件ID")
	private String fileId;

	@Schema(description = "样本文件名")
	private String fileName;

	@Schema(description = "样本文件路径")
	private String filePath;

	@Schema(description = "音频MIME类型")
	private String contentType;

	@Schema(description = "音频时长（毫秒）")
	private Integer durationMs;

	@Schema(description = "采样率（Hz）")
	private Integer sampleRate;

	@Schema(description = "样本朗读文本")
	private String transcript;

	@Schema(description = "是否已确认声音授权")
	private Boolean consentConfirmed;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "错误信息")
	private String errorMessage;

	@Schema(description = "创建时间")
	private Instant createTime;

}
