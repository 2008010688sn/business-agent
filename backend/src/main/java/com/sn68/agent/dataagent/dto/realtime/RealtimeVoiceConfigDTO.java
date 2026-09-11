/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.realtime;

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
 * Agent 实时语音配置：约定语音会话的模型组合、音频格式与超时策略。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "实时语音配置")
public class RealtimeVoiceConfigDTO {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "配置名称")
	private String configName;

	@Schema(description = "运行时模式")
	private String runtimeMode;

	@Schema(description = "传输通道（websocket/webrtc）")
	private String transport;

	@Schema(description = "对话模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long chatModelConfigId;

	@Schema(description = "ASR模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long asrModelConfigId;

	@Schema(description = "TTS模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long ttsModelConfigId;

	@Schema(description = "实时语音模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long realtimeVoiceModelConfigId;

	@Schema(description = "音色档案ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long voiceProfileId;

	@Schema(description = "输入音频格式")
	private String inputAudioFormat;

	@Schema(description = "输入采样率（Hz）")
	private Integer inputSampleRate;

	@Schema(description = "输出音频格式")
	private String outputAudioFormat;

	@Schema(description = "输出采样率（Hz）")
	private Integer outputSampleRate;

	@Schema(description = "是否启用VAD（语音活动检测）")
	private Boolean vadEnabled;

	@Schema(description = "是否允许打断")
	private Boolean allowInterrupt;

	@Schema(description = "是否启用部分转写结果推送")
	private Boolean partialTranscriptEnabled;

	@Schema(description = "是否记录文本消息")
	private Boolean textRecordEnabled;

	@Schema(description = "是否启用降级")
	private Boolean fallbackEnabled;

	@Schema(description = "降级策略")
	private String fallbackStrategy;

	@Schema(description = "连接超时（毫秒）")
	private Integer connectTimeoutMs;

	@Schema(description = "读取超时（毫秒）")
	private Integer readTimeoutMs;

	@Schema(description = "空闲超时（毫秒）")
	private Integer idleTimeoutMs;

	@Schema(description = "单轮最大时长（毫秒）")
	private Integer maxTurnDurationMs;

	@Schema(description = "心跳间隔（毫秒）")
	private Integer heartbeatIntervalMs;

	@Schema(description = "是否默认")
	private Boolean isDefault;

	@Schema(description = "是否启用")
	private Boolean enabled;

	@Schema(description = "扩展选项")
	@Builder.Default
	private Map<String, Object> options = new LinkedHashMap<>();

}
