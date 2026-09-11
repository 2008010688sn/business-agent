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
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型 TTS（语音合成）能力配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "模型TTS配置")
public class ModelTtsConfigDTO {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "语音合成接口路径")
	private String speechPath;

	@Schema(description = "流式语音合成接口路径")
	private String speechStreamPath;

	@Schema(description = "TTS协议")
	private String ttsProtocol;

	@Schema(description = "是否启用流式合成")
	private Boolean streamingEnabled;

	@Schema(description = "默认音频格式")
	private String defaultFormat;

	@Schema(description = "默认采样率（Hz）")
	private Integer defaultSampleRate;

	@Schema(description = "扩展选项")
	@Builder.Default
	private Map<String, Object> options = new LinkedHashMap<>();

}
