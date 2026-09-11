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
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TTS 音色档案：描述单个可用音色及其语速、音调等合成参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "TTS音色档案")
public class TtsVoiceProfileDTO {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "所属TTS配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long ttsConfigId;

	@Schema(description = "音色档案名称")
	private String profileName;

	@Schema(description = "音色名称")
	private String voiceName;

	@Schema(description = "音色展示标签")
	private String voiceLabel;

	@Schema(description = "音色来源")
	private String voiceSource;

	@Schema(description = "克隆音源样本ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long sampleId;

	@Schema(description = "语言编码")
	private String languageCode;

	@Schema(description = "音色性别")
	private String gender;

	@Schema(description = "语速倍率")
	private BigDecimal speechSpeed;

	@Schema(description = "音调")
	private BigDecimal pitch;

	@Schema(description = "音量增益")
	private BigDecimal volumeGain;

	@Schema(description = "合成音频格式")
	private String speechFormat;

	@Schema(description = "采样率（Hz）")
	private Integer sampleRate;

	@Schema(description = "扩展选项")
	@Builder.Default
	private Map<String, Object> options = new LinkedHashMap<>();

	@Schema(description = "是否默认")
	private Boolean isDefault;

	@Schema(description = "是否启用")
	private Boolean enabled;

	@Schema(description = "克隆音源样本信息")
	private TtsVoiceSampleResp sample;

}
