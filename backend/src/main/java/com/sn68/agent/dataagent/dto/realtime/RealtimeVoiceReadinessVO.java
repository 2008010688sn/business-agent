/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.realtime;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实时语音就绪状态检查结果：逐项给出模型、音色与配置是否就绪。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "实时语音就绪状态")
public class RealtimeVoiceReadinessVO {

	@Schema(description = "整体是否就绪")
	private Boolean ready;

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "对话模型是否就绪")
	private Boolean chatModelReady;

	@Schema(description = "ASR模型是否就绪")
	private Boolean asrModelReady;

	@Schema(description = "TTS模型是否就绪")
	private Boolean ttsModelReady;

	@Schema(description = "音色档案是否就绪")
	private Boolean voiceProfileReady;

	@Schema(description = "实时语音模型是否就绪")
	private Boolean realtimeVoiceModelReady;

	@Schema(description = "实时语音配置是否就绪")
	private Boolean realtimeConfigReady;

	@Schema(description = "实时语音配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long realtimeConfigId;

	@Schema(description = "运行时模式")
	private String runtimeMode;

	@Schema(description = "缺失项列表")
	@Builder.Default
	private List<String> missingItems = new ArrayList<>();

	@Schema(description = "生效的实时语音配置")
	private RealtimeVoiceConfigDTO config;

}
