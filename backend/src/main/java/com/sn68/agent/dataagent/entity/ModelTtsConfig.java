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
import com.sn68.agent.framework.commons.entity.SuperEntity;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 模型Tts配置实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "model_tts_config", autoResultMap = true)
@Schema(description = "TTS 能力配置")
public class ModelTtsConfig extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "模型配置ID")
	private Long modelConfigId;

	@Schema(description = "路径字段")
	private String speechPath;

	@Schema(description = "路径字段")
	private String speechStreamPath;

	@Schema(description = "ttsProtocol字段")
	private String ttsProtocol;

	@Schema(description = "启用状态")
	private Boolean streamingEnabled;

	@Schema(description = "默认标识字段")
	private String defaultFormat;

	@Schema(description = "默认标识字段")
	private Integer defaultSampleRate;

	@Schema(description = "options字段")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String options;

}
