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
 * 一体化实时语音模型（Realtime API）能力配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "模型实时语音配置")
public class ModelRealtimeVoiceConfigDTO {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "WebSocket连接地址")
	private String websocketUrl;

	@Schema(description = "WebRTC连接地址")
	private String webrtcUrl;

	@Schema(description = "输入音频格式")
	private String inputAudioFormat;

	@Schema(description = "输出音频格式")
	private String outputAudioFormat;

	@Schema(description = "输入采样率（Hz）")
	private Integer inputSampleRate;

	@Schema(description = "输出采样率（Hz）")
	private Integer outputSampleRate;

	@Schema(description = "轮次检测类型")
	private String turnDetectionType;

	@Schema(description = "音色名称")
	private String voiceName;

	@Schema(description = "是否启用转写")
	private Boolean transcriptEnabled;

	@Schema(description = "是否支持工具调用")
	private Boolean toolCallEnabled;

	@Schema(description = "是否支持打断")
	private Boolean interruptSupported;

	@Schema(description = "扩展选项")
	@Builder.Default
	private Map<String, Object> options = new LinkedHashMap<>();

}
