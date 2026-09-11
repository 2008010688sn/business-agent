/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.ModelConfigIdReq;
import com.sn68.agent.dataagent.dto.agent.AgentIdFilterReq;
import com.sn68.agent.dataagent.dto.realtime.ModelAsrConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.ModelRealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceReadinessVO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceReadinessQueryReq;
import com.sn68.agent.dataagent.service.realtime.RealtimeVoiceConfigService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护 ASR、实时语音、TTS 与会话配置就绪状态。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "实时语音配置", description = "维护 ASR、实时语音、TTS 与会话配置就绪状态")
public class RealtimeVoiceConfigController {

	private final RealtimeVoiceConfigService realtimeVoiceConfigService;

	@Operation(summary = "查询模型ASR配置", description = "按模型配置 ID 查询语音识别（ASR）参数。")
	@PostMapping("/model-config/asr/query")
	public ModelAsrConfigDTO getAsrConfig(@RequestBody ModelConfigIdReq request) {
		return realtimeVoiceConfigService.getAsrConfig(requireModelConfigId(request == null ? null : request.modelConfigId()));
	}

	@Operation(summary = "修改模型ASR配置", description = "按模型配置 ID 保存语音识别（ASR）参数。")
	@AccessLog(module = "实时语音配置", description = "修改模型ASR配置")
	@PutMapping("/model-config/asr/modify")
	public ModelAsrConfigDTO saveAsrConfig(@RequestBody ModelAsrConfigDTO request) {
		return realtimeVoiceConfigService.saveAsrConfig(requireModelConfigId(request == null ? null : request.getModelConfigId()), request);
	}

	@Operation(summary = "查询模型实时语音配置", description = "按模型配置 ID 查询实时语音模型参数。")
	@PostMapping("/model-config/realtime-voice/query")
	public ModelRealtimeVoiceConfigDTO getRealtimeVoiceModelConfig(@RequestBody ModelConfigIdReq request) {
		return realtimeVoiceConfigService.getRealtimeVoiceModelConfig(requireModelConfigId(request == null ? null : request.modelConfigId()));
	}

	@Operation(summary = "修改模型实时语音配置", description = "按模型配置 ID 保存实时语音模型参数。")
	@AccessLog(module = "实时语音配置", description = "修改模型实时语音配置")
	@PutMapping("/model-config/realtime-voice/modify")
	public ModelRealtimeVoiceConfigDTO saveRealtimeVoiceModelConfig(@RequestBody ModelRealtimeVoiceConfigDTO request) {
		return realtimeVoiceConfigService.saveRealtimeVoiceModelConfig(requireModelConfigId(request == null ? null : request.getModelConfigId()), request);
	}

	@Operation(summary = "查询实时语音配置清单", description = "查询实时语音配置清单，用于实时语音配置相关管理和运行场景。")
	@PostMapping("/realtime-voice/configs/query")
	public List<RealtimeVoiceConfigDTO> listConfigs(@RequestBody(required = false) AgentIdFilterReq request) {
		return realtimeVoiceConfigService.listConfigs(request == null ? null : request.agentId());
	}

	@Operation(summary = "修改Agent 实时语音会话配置", description = "保存 Agent 维度的实时语音会话配置。")
	@AccessLog(module = "实时语音配置", description = "修改Agent 实时语音会话配置")
	@PutMapping("/realtime-voice/configs")
	public RealtimeVoiceConfigDTO saveConfig(@RequestBody RealtimeVoiceConfigDTO request) {
		return realtimeVoiceConfigService.saveConfig(request);
	}

	@Operation(summary = "创建实时语音配置默认配置", description = "创建实时语音配置默认配置，用于实时语音配置相关管理和运行场景。")
	@AccessLog(module = "实时语音配置", description = "创建实时语音默认配置")
	@PostMapping("/realtime-voice/configs-default")
	public RealtimeVoiceReadinessVO generateDefault(@RequestBody(required = false) AgentIdFilterReq request) {
		return realtimeVoiceConfigService.generateDefault(request == null ? null : request.agentId());
	}

	@Operation(summary = "查询实时语音配置就绪状态", description = "查询实时语音配置就绪状态，用于实时语音配置相关管理和运行场景。")
	@PostMapping("/realtime-voice/readiness/query")
	public RealtimeVoiceReadinessVO readiness(@RequestBody(required = false) RealtimeVoiceReadinessQueryReq request) {
		return realtimeVoiceConfigService.readiness(request == null ? null : request.agentId(),
				request == null ? null : request.realtimeConfigId());
	}

	private Long requireModelConfigId(Long modelConfigId) {
		if (modelConfigId == null) {
			throw CheckedException.badRequest("modelConfigId不能为空");
		}
		return modelConfigId;
	}

}
