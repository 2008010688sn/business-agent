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
 * 一体化实时语音模型配置。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "model_realtime_voice_config", autoResultMap = true)
@Schema(description = "一体化实时语音模型配置")
public class ModelRealtimeVoiceConfig extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "关联模型配置ID")
	private Long modelConfigId;

	@Schema(description = "WebSocket地址")
	private String websocketUrl;

	@Schema(description = "WebRTC地址")
	private String webrtcUrl;

	@Schema(description = "输入音频格式")
	private String inputAudioFormat;

	@Schema(description = "输出音频格式")
	private String outputAudioFormat;

	@Schema(description = "输入采样率")
	private Integer inputSampleRate;

	@Schema(description = "输出采样率")
	private Integer outputSampleRate;

	@Schema(description = "轮次检测类型")
	private String turnDetectionType;

	@Schema(description = "默认音色名称")
	private String voiceName;

	@Schema(description = "是否返回转写文本")
	private Boolean transcriptEnabled;

	@Schema(description = "是否支持工具调用")
	private Boolean toolCallEnabled;

	@Schema(description = "是否支持打断")
	private Boolean interruptSupported;

	@Schema(description = "扩展配置")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String options;

}
