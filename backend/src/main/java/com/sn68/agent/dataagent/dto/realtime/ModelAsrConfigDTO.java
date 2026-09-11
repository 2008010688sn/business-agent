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
 * 模型 ASR（语音识别）能力配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "模型ASR配置")
public class ModelAsrConfigDTO {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "语音转写接口路径")
	private String transcriptionPath;

	@Schema(description = "流式语音转写接口路径")
	private String transcriptionStreamPath;

	@Schema(description = "ASR协议")
	private String asrProtocol;

	@Schema(description = "是否启用流式转写")
	private Boolean streamingEnabled;

	@Schema(description = "默认音频格式")
	private String defaultAudioFormat;

	@Schema(description = "默认采样率（Hz）")
	private Integer defaultSampleRate;

	@Schema(description = "是否支持VAD（语音活动检测）")
	private Boolean vadSupported;

	@Schema(description = "是否支持热词")
	private Boolean hotwordSupported;

	@Schema(description = "扩展选项")
	@Builder.Default
	private Map<String, Object> options = new LinkedHashMap<>();

}
