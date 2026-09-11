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
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 音频语音合成请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "音频语音合成请求")
public class AudioSpeechReq {

	@Schema(description = "待合成的文本内容")
	@NotBlank(message = "text must not be empty")
	private String text;

	@Schema(description = "模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "音色档案ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long voiceProfileId;

	@Schema(description = "音频输出格式")
	private String format;

	@Schema(description = "采样率（Hz）")
	private Integer sampleRate;

	@Schema(description = "语速倍率")
	private BigDecimal speed;

	@Schema(description = "扩展选项")
	@Builder.Default
	private Map<String, Object> options = new LinkedHashMap<>();

}
