/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * ASR能力配置。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "model_asr_config", autoResultMap = true)
@Schema(description = "ASR能力配置")
public class ModelAsrConfig extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "关联模型配置ID")
	private Long modelConfigId;

	@Schema(description = "普通语音转写路径")
	private String transcriptionPath;

	@Schema(description = "流式语音转写地址")
	private String transcriptionStreamPath;

	@Schema(description = "ASR协议")
	private String asrProtocol;

	@Schema(description = "是否支持流式识别")
	private Boolean streamingEnabled;

	@Schema(description = "默认音频格式")
	private String defaultAudioFormat;

	@Schema(description = "默认采样率")
	private Integer defaultSampleRate;

	@Schema(description = "是否支持VAD")
	private Boolean vadSupported;

	@Schema(description = "是否支持热词")
	private Boolean hotwordSupported;

	@Schema(description = "扩展配置")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String options;

}
