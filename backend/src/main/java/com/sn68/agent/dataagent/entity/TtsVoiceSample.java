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

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Tts语音Sample实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("tts_voice_sample")
@Schema(description = "TTS 本地参考音色样本")
public class TtsVoiceSample extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "文件字段")
	private String fileId;

	@Schema(description = "名称文件字段")
	private String fileName;

	@Schema(description = "文件路径字段")
	private String filePath;

	@Schema(description = "类型内容")
	private String contentType;

	@Schema(description = "durationMs字段")
	private Integer durationMs;

	@Schema(description = "sampleRate字段")
	private Integer sampleRate;

	@Schema(description = "transcript字段")
	private String transcript;

	@Schema(description = "consentConfirmed字段")
	private Boolean consentConfirmed;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "错误信息")
	private String errorMessage;

}
