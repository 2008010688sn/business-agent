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
 * 智能体实时语音配置。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "realtime_voice_config", autoResultMap = true)
@Schema(description = "智能体实时语音配置")
public class RealtimeVoiceConfig extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "智能体ID，空表示全局默认")
	private Long agentId;

	@Schema(description = "配置名称")
	private String configName;

	@Schema(description = "运行模式")
	private String runtimeMode;

	@Schema(description = "传输方式")
	private String transport;

	@Schema(description = "对话模型配置ID")
	private Long chatModelConfigId;

	@Schema(description = "ASR模型配置ID")
	private Long asrModelConfigId;

	@Schema(description = "TTS模型配置ID")
	private Long ttsModelConfigId;

	@Schema(description = "一体化实时语音模型配置ID")
	private Long realtimeVoiceModelConfigId;

	@Schema(description = "TTS音色Profile ID")
	private Long voiceProfileId;

	@Schema(description = "输入音频格式")
	private String inputAudioFormat;

	@Schema(description = "输入采样率")
	private Integer inputSampleRate;

	@Schema(description = "输出音频格式")
	private String outputAudioFormat;

	@Schema(description = "输出采样率")
	private Integer outputSampleRate;

	@Schema(description = "是否启用VAD")
	private Boolean vadEnabled;

	@Schema(description = "是否允许打断")
	private Boolean allowInterrupt;

	@Schema(description = "是否显示临时字幕")
	private Boolean partialTranscriptEnabled;

	@Schema(description = "是否保存文字记录")
	private Boolean textRecordEnabled;

	@Schema(description = "是否启用降级")
	private Boolean fallbackEnabled;

	@Schema(description = "降级策略")
	private String fallbackStrategy;

	@Schema(description = "连接超时毫秒")
	private Integer connectTimeoutMs;

	@Schema(description = "读取超时毫秒")
	private Integer readTimeoutMs;

	@Schema(description = "空闲超时毫秒")
	private Integer idleTimeoutMs;

	@Schema(description = "最大单轮时长毫秒")
	private Integer maxTurnDurationMs;

	@Schema(description = "心跳间隔毫秒")
	private Integer heartbeatIntervalMs;

	@Schema(description = "是否默认配置")
	private Boolean isDefault;

	@Schema(description = "是否启用")
	private Boolean enabled;

	@Schema(description = "扩展配置")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String options;

}
