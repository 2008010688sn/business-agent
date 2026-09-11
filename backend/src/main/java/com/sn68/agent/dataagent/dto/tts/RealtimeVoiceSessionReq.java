/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
 * 实时语音会话请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "实时语音会话请求")
public class RealtimeVoiceSessionReq {

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "线程ID")
	private String threadId;

	@Schema(description = "实时语音配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long realtimeConfigId;

	@Schema(description = "运行时模式")
	private String runtimeMode;

	@Schema(description = "会话模式")
	private String mode;

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

	@Schema(description = "是否允许打断")
	private Boolean allowInterrupt;

	@Schema(description = "是否启用VAD（语音活动检测）")
	private Boolean vadEnabled;

	@Schema(description = "会话扩展选项")
	@Builder.Default
	private Map<String, Object> sessionOptions = new LinkedHashMap<>();

}
