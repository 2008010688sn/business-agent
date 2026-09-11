/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime;

import com.sn68.agent.dataagent.dto.realtime.ModelAsrConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.ModelRealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceReadinessVO;
import java.util.List;

/**
 * 实时语音配置服务契约。
 */
public interface RealtimeVoiceConfigService {

	/**
	 * 查询实时语音配置。
	 */
	ModelAsrConfigDTO getAsrConfig(Long modelConfigId);

	/**
	 * 保存实时语音配置。
	 */
	ModelAsrConfigDTO saveAsrConfig(Long modelConfigId, ModelAsrConfigDTO request);

	/**
	 * 查询实时语音配置。
	 */
	ModelRealtimeVoiceConfigDTO getRealtimeVoiceModelConfig(Long modelConfigId);

	/**
	 * 保存实时语音配置。
	 */
	ModelRealtimeVoiceConfigDTO saveRealtimeVoiceModelConfig(Long modelConfigId, ModelRealtimeVoiceConfigDTO request);

	/**
	 * 查询实时语音配置。
	 */
	List<RealtimeVoiceConfigDTO> listConfigs(Long agentId);

	/**
	 * 保存实时语音配置。
	 */
	RealtimeVoiceConfigDTO saveConfig(RealtimeVoiceConfigDTO request);

	/**
	 * 创建实时语音配置。
	 */
	RealtimeVoiceReadinessVO generateDefault(Long agentId);

	/**
	 * 处理实时语音配置。
	 */
	RealtimeVoiceReadinessVO readiness(Long agentId, Long realtimeConfigId);

	/**
	 * 查询实时语音配置。
	 */
	RealtimeVoiceConfigDTO resolveEffectiveConfig(Long agentId, Long realtimeConfigId);

}
