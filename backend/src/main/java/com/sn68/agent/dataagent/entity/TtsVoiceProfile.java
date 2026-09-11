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
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Tts语音Profile实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "tts_voice_profile", autoResultMap = true)
@Schema(description = "TTS 音色 Profile")
public class TtsVoiceProfile extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "配置")
	private Long ttsConfigId;

	@Schema(description = "名称文件字段")
	private String profileName;

	@Schema(description = "名称语音字段")
	private String voiceName;

	@Schema(description = "语音字段")
	private String voiceLabel;

	@Schema(description = "语音来源字段")
	private String voiceSource;

	@Schema(description = "sampleId字段")
	private Long sampleId;

	@Schema(description = "编码")
	private String languageCode;

	@Schema(description = "gender字段")
	private String gender;

	@Schema(description = "speechSpeed字段")
	private BigDecimal speechSpeed;

	@Schema(description = "pitch字段")
	private BigDecimal pitch;

	@Schema(description = "volumeGain字段")
	private BigDecimal volumeGain;

	@Schema(description = "speechFormat字段")
	private String speechFormat;

	@Schema(description = "sampleRate字段")
	private Integer sampleRate;

	@Schema(description = "options字段")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String options;

	@Schema(description = "是否默认")
	private Boolean isDefault;

	@Schema(description = "是否启用")
	private Boolean enabled;

}
