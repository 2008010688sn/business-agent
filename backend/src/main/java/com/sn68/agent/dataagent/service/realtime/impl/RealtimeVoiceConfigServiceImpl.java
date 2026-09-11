/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime.impl;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.ModelAsrConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.ModelRealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceReadinessVO;
import com.sn68.agent.dataagent.entity.ModelAsrConfig;
import com.sn68.agent.dataagent.entity.ModelRealtimeVoiceConfig;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.dataagent.entity.RealtimeVoiceConfig;
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.enums.RealtimeVoiceErrorDict;
import com.sn68.agent.dataagent.enums.RealtimeVoiceRuntimeMode;
import com.sn68.agent.dataagent.enums.TransportType;
import com.sn68.agent.dataagent.repository.ModelAsrConfigMapper;
import com.sn68.agent.dataagent.repository.ModelRealtimeVoiceConfigMapper;
import com.sn68.agent.dataagent.repository.ModelTtsConfigMapper;
import com.sn68.agent.dataagent.repository.RealtimeVoiceConfigMapper;
import com.sn68.agent.dataagent.repository.TtsVoiceProfileMapper;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.dataagent.service.realtime.RealtimeVoiceConfigService;
import com.sn68.agent.dataagent.service.tts.impl.TtsJsonSupport;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 实时语音配置服务组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeVoiceConfigServiceImpl implements RealtimeVoiceConfigService {

	private static final Set<Integer> SUPPORTED_SAMPLE_RATES = Set.of(16000, 24000, 48000);

	private static final String DEFAULT_AUDIO_FORMAT = "PCM16";

	private static final String REALTIME_VOICE_RESOURCE = "实时语音配置";

	private final ModelAsrConfigMapper asrConfigMapper;

	private final ModelRealtimeVoiceConfigMapper realtimeVoiceModelConfigMapper;

	private final RealtimeVoiceConfigMapper realtimeVoiceConfigMapper;

	private final ModelTtsConfigMapper ttsConfigMapper;

	private final TtsVoiceProfileMapper voiceProfileMapper;

	private final ModelConfigDataService modelConfigDataService;

	private final TtsJsonSupport jsonSupport;

	private final PlatformScopePermissionService platformScopePermissionService;

	@Override
	public ModelAsrConfigDTO getAsrConfig(Long modelConfigId) {
		if (modelConfigId == null) {
			return ModelAsrConfigDTO.builder().build();
		}
		return toDTO(asrConfigMapper.findByModelConfigId(modelConfigId));
	}

	/**
	 * 保存指定模型的 ASR 能力配置：存在则整体覆盖更新，不存在则新建。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public ModelAsrConfigDTO saveAsrConfig(Long modelConfigId, ModelAsrConfigDTO request) {
		if (modelConfigId == null) {
			throw badRequest(RealtimeVoiceErrorDict.CONFIG_NOT_READY);
		}
		ModelAsrConfig existing = asrConfigMapper.findByModelConfigId(modelConfigId);
		ModelAsrConfig entity = toEntity(request, modelConfigId);
		Instant now = Instant.now();
		if (existing == null) {
			entity.setCreateTime(now);
			entity.setLastModifyTime(now);
			asrConfigMapper.insert(entity);
		}
		else {
			entity.setId(existing.getId());
			entity.setCreateTime(existing.getCreateTime());
			entity.setLastModifyTime(now);
			asrConfigMapper.updateById(entity);
		}
		return getAsrConfig(modelConfigId);
	}

	@Override
	public ModelRealtimeVoiceConfigDTO getRealtimeVoiceModelConfig(Long modelConfigId) {
		if (modelConfigId == null) {
			return ModelRealtimeVoiceConfigDTO.builder().build();
		}
		return toDTO(realtimeVoiceModelConfigMapper.findByModelConfigId(modelConfigId));
	}

	/**
	 * 保存指定模型的一体化实时语音能力配置：存在则整体覆盖更新，不存在则新建。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public ModelRealtimeVoiceConfigDTO saveRealtimeVoiceModelConfig(Long modelConfigId,
			ModelRealtimeVoiceConfigDTO request) {
		if (modelConfigId == null) {
			throw badRequest(RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY);
		}
		ModelRealtimeVoiceConfig existing = realtimeVoiceModelConfigMapper.findByModelConfigId(modelConfigId);
		ModelRealtimeVoiceConfig entity = toEntity(request, modelConfigId);
		Instant now = Instant.now();
		if (existing == null) {
			entity.setCreateTime(now);
			entity.setLastModifyTime(now);
			realtimeVoiceModelConfigMapper.insert(entity);
		}
		else {
			entity.setId(existing.getId());
			entity.setCreateTime(existing.getCreateTime());
			entity.setLastModifyTime(now);
			realtimeVoiceModelConfigMapper.updateById(entity);
		}
		return getRealtimeVoiceModelConfig(modelConfigId);
	}

	/**
	 * 查询指定 Agent 名下的实时语音配置列表。
	 */
	@Override
	public List<RealtimeVoiceConfigDTO> listConfigs(Long agentId) {
		return realtimeVoiceConfigMapper.findByAgentId(requireCurrentTenantId(), agentId).stream().map(this::toDTO)
			.toList();
	}

	/**
	 * 保存实时语音配置：事务内校验采样率/超时等取值，并维护同 Agent 下默认配置唯一。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public RealtimeVoiceConfigDTO saveConfig(RealtimeVoiceConfigDTO request) {
		RealtimeVoiceConfig entity = toEntity(request);
		validateConfig(entity);
		String tenantId = requireCurrentTenantId();
		entity.setTenantId(tenantId);
		RealtimeVoiceConfig existing = entity.getId() == null ? null : realtimeVoiceConfigMapper.findById(entity.getId());
		if (existing != null && !tenantId.equals(existing.getTenantId())) {
			throw CheckedException.notFound("实时语音配置不存在");
		}
		Instant now = Instant.now();
		if (existing == null) {
			entity.setCreateTime(now);
			entity.setLastModifyTime(now);
			realtimeVoiceConfigMapper.insert(entity);
		}
		else {
			entity.setCreateTime(existing.getCreateTime());
			entity.setLastModifyTime(now);
			realtimeVoiceConfigMapper.updateById(entity);
		}
		if (Boolean.TRUE.equals(entity.getIsDefault())) {
			realtimeVoiceConfigMapper.clearDefault(tenantId, entity.getAgentId(), entity.getId());
		}
		return toDTO(realtimeVoiceConfigMapper.findById(entity.getId()));
	}

	/**
	 * 按当前启用的实时语音模型生成（或覆盖）默认配置，并返回生成后的就绪状态。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public RealtimeVoiceReadinessVO generateDefault(Long agentId) {
		ModelConfigDTO realtimeModel = modelConfigDataService.getActiveConfigByType(ModelType.REALTIME_VOICE);
		ModelRealtimeVoiceConfig realtimeModelConfig = realtimeModel == null ? null
				: realtimeVoiceModelConfigMapper.findByModelConfigId(realtimeModel.getId());
		if (realtimeModel != null && realtimeModelConfig != null) {
			RealtimeVoiceConfig existing = findScopedDefault(agentId);
			RealtimeVoiceConfigDTO defaultConfig = RealtimeVoiceConfigDTO.builder()
				.id(existing == null ? null : existing.getId())
				.agentId(agentId)
				.configName(agentId == null ? "Default realtime voice" : "Agent default realtime voice")
				.runtimeMode(RealtimeVoiceRuntimeMode.REALTIME.getCode())
				.transport(TransportType.WEBSOCKET.getCode())
				.realtimeVoiceModelConfigId(realtimeModel.getId())
				.inputAudioFormat(firstText(realtimeModelConfig.getInputAudioFormat(), DEFAULT_AUDIO_FORMAT))
				.inputSampleRate(realtimeModelConfig.getInputSampleRate() == null ? 24000
						: realtimeModelConfig.getInputSampleRate())
				.outputAudioFormat(firstText(realtimeModelConfig.getOutputAudioFormat(), DEFAULT_AUDIO_FORMAT))
				.outputSampleRate(realtimeModelConfig.getOutputSampleRate() == null ? 24000
						: realtimeModelConfig.getOutputSampleRate())
				.vadEnabled(true)
				.allowInterrupt(!Boolean.FALSE.equals(realtimeModelConfig.getInterruptSupported()))
				.partialTranscriptEnabled(!Boolean.FALSE.equals(realtimeModelConfig.getTranscriptEnabled()))
				.textRecordEnabled(false)
				.fallbackEnabled(true)
				.fallbackStrategy("RECORDING_ASR")
				.connectTimeoutMs(10000)
				.readTimeoutMs(60000)
				.idleTimeoutMs(60000)
				.maxTurnDurationMs(60000)
				.heartbeatIntervalMs(15000)
				.isDefault(true)
				.enabled(true)
				.options(Map.of())
				.build();
			saveConfig(defaultConfig);
			return readiness(agentId, null);
		}

		ModelConfigDTO chat = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
		ModelConfigDTO asr = modelConfigDataService.getActiveConfigByType(ModelType.AUDIO_TRANSCRIPTION);
		ModelConfigDTO tts = modelConfigDataService.getActiveConfigByType(ModelType.TEXT_TO_SPEECH);
		ModelTtsConfig ttsConfig = tts == null ? null : ttsConfigMapper.findByModelConfigId(tts.getId());
		TtsVoiceProfile voiceProfile = ttsConfig == null ? null : voiceProfileMapper.findDefaultByTtsConfigId(ttsConfig.getId());
		if (chat == null || asr == null || tts == null || ttsConfig == null || voiceProfile == null) {
			return readiness(agentId, null);
		}

		RealtimeVoiceConfig existing = findScopedDefault(agentId);
		RealtimeVoiceConfigDTO defaultConfig = RealtimeVoiceConfigDTO.builder()
			.id(existing == null ? null : existing.getId())
			.agentId(agentId)
			.configName(agentId == null ? "全局默认实时语音" : "智能体默认实时语音")
			.runtimeMode(RealtimeVoiceRuntimeMode.PIPELINE.getCode())
			.transport(TransportType.WEBSOCKET.getCode())
			.chatModelConfigId(chat.getId())
			.asrModelConfigId(asr.getId())
			.ttsModelConfigId(tts.getId())
			.voiceProfileId(voiceProfile.getId())
			.inputAudioFormat(DEFAULT_AUDIO_FORMAT)
			.inputSampleRate(16000)
			.outputAudioFormat(DEFAULT_AUDIO_FORMAT)
			.outputSampleRate(24000)
			.vadEnabled(true)
			.allowInterrupt(true)
			.partialTranscriptEnabled(true)
			.textRecordEnabled(true)
			.fallbackEnabled(true)
			.fallbackStrategy("RECORDING_ASR")
			.connectTimeoutMs(10000)
			.readTimeoutMs(60000)
			.idleTimeoutMs(60000)
			.maxTurnDurationMs(60000)
			.heartbeatIntervalMs(15000)
			.isDefault(true)
			.enabled(true)
			.options(Map.of())
			.build();
		saveConfig(defaultConfig);
		return readiness(agentId, null);
	}

	/**
	 * 评估实时语音能力的就绪状态：按运行模式检查配置、模型与音色是否齐备，缺失项写入提示列表。
	 */
	@Override
	public RealtimeVoiceReadinessVO readiness(Long agentId, Long realtimeConfigId) {
		RealtimeVoiceConfig config = resolveConfig(agentId, realtimeConfigId);
		RealtimeVoiceConfigDTO configDTO = toDTO(config);
		List<String> missingItems = new ArrayList<>();
		boolean configReady = config != null && !Boolean.FALSE.equals(config.getEnabled());
		if (!configReady) {
			missingItems.add("缺实时语音配置");
		}

		RealtimeVoiceRuntimeMode runtimeMode = RealtimeVoiceRuntimeMode
			.fromCode(config == null ? null : config.getRuntimeMode());
		boolean realtimeVoiceReady = false;
		CascadeReadiness cascade = CascadeReadiness.notReady();
		if (runtimeMode == RealtimeVoiceRuntimeMode.REALTIME) {
			realtimeVoiceReady = evaluateRealtimeModeReadiness(config, missingItems);
		}
		else {
			cascade = evaluateCascadeModeReadiness(config, missingItems);
		}

		boolean ready = configReady && (runtimeMode == RealtimeVoiceRuntimeMode.REALTIME ? realtimeVoiceReady
				: cascade.chatReady() && cascade.asrReady() && cascade.ttsReady() && cascade.voiceProfileReady());
		return RealtimeVoiceReadinessVO.builder()
			.ready(ready)
			.agentId(agentId)
			.chatModelReady(cascade.chatReady())
			.asrModelReady(cascade.asrReady())
			.ttsModelReady(cascade.ttsReady())
			.voiceProfileReady(cascade.voiceProfileReady())
			.realtimeVoiceModelReady(realtimeVoiceReady)
			.realtimeConfigReady(configReady)
			.realtimeConfigId(config == null ? null : config.getId())
			.runtimeMode(runtimeMode.getCode())
			.missingItems(missingItems)
			.config(configDTO)
			.build();
	}

	/**
	 * REALTIME 一体化模式：检查实时语音模型、provider key 与 WebSocket endpoint 是否齐备。
	 * @return 实时语音模型是否就绪
	 */
	private boolean evaluateRealtimeModeReadiness(RealtimeVoiceConfig config, List<String> missingItems) {
		Long realtimeModelId = firstId(config == null ? null : config.getRealtimeVoiceModelConfigId(),
				activeModelId(ModelType.REALTIME_VOICE));
		ModelConfigDTO realtimeVoiceModel = resolveModelConfig(realtimeModelId, ModelType.REALTIME_VOICE);
		ModelRealtimeVoiceConfig realtimeVoiceConfig = realtimeModelId == null ? null
				: realtimeVoiceModelConfigMapper.findByModelConfigId(realtimeModelId);
		boolean realtimeEndpointReady = realtimeVoiceConfig != null
				&& (!isWsTransport(config) || StringUtils.hasText(realtimeVoiceConfig.getWebsocketUrl())
						|| (realtimeVoiceModel != null && StringUtils.hasText(realtimeVoiceModel.getBaseUrl())));
		boolean realtimeVoiceReady = modelReady(realtimeVoiceModel, ModelType.REALTIME_VOICE) && realtimeEndpointReady;
		if (realtimeVoiceModel == null) {
			missingItems.add("缺 REALTIME_VOICE 模型或一体化实时语音配置");
			return realtimeVoiceReady;
		}
		if (isProviderKeyMissing(realtimeVoiceModel)) {
			missingItems.add("REALTIME_VOICE provider key 未配置");
		}
		if (realtimeVoiceConfig == null) {
			missingItems.add("缺 REALTIME_VOICE 一体化实时语音配置");
		}
		else if (isWsTransport(config) && !StringUtils.hasText(realtimeVoiceConfig.getWebsocketUrl())
				&& !StringUtils.hasText(realtimeVoiceModel.getBaseUrl())) {
			missingItems.add("缺 REALTIME_VOICE WebSocket endpoint");
		}
		return realtimeVoiceReady;
	}

	/**
	 * 级联模式：逐项检查 CHAT、ASR、TTS 模型及音色配置，缺失项写入提示列表。
	 */
	private CascadeReadiness evaluateCascadeModeReadiness(RealtimeVoiceConfig config, List<String> missingItems) {
		Long chatModelId = firstId(config == null ? null : config.getChatModelConfigId(), activeModelId(ModelType.CHAT));
		Long asrModelId = firstId(config == null ? null : config.getAsrModelConfigId(),
				activeModelId(ModelType.AUDIO_TRANSCRIPTION));
		Long ttsModelId = firstId(config == null ? null : config.getTtsModelConfigId(),
				activeModelId(ModelType.TEXT_TO_SPEECH));
		ModelConfigDTO chatModel = resolveModelConfig(chatModelId, ModelType.CHAT);
		ModelConfigDTO asrModel = resolveModelConfig(asrModelId, ModelType.AUDIO_TRANSCRIPTION);
		ModelConfigDTO ttsModel = resolveModelConfig(ttsModelId, ModelType.TEXT_TO_SPEECH);
		boolean chatReady = modelReady(chatModel, ModelType.CHAT);
		boolean asrReady = modelReady(asrModel, ModelType.AUDIO_TRANSCRIPTION);
		boolean ttsReady = modelReady(ttsModel, ModelType.TEXT_TO_SPEECH);
		if (chatModel == null) {
			missingItems.add("缺 CHAT 模型");
		}
		else if (isProviderKeyMissing(chatModel)) {
			missingItems.add("CHAT provider key 未配置");
		}
		if (asrModel == null) {
			missingItems.add("缺 ASR 模型");
		}
		else if (isProviderKeyMissing(asrModel)) {
			missingItems.add("ASR provider key 未配置");
		}
		if (ttsModel == null) {
			missingItems.add("缺 TTS 模型");
		}
		else if (isProviderKeyMissing(ttsModel)) {
			missingItems.add("TTS provider key 未配置");
		}
		ModelTtsConfig ttsConfig = ttsModel == null ? null : ttsConfigMapper.findByModelConfigId(ttsModelId);
		Long voiceProfileId = config == null ? null : config.getVoiceProfileId();
		TtsVoiceProfile voiceProfile = resolveVoiceProfile(ttsConfig, voiceProfileId);
		boolean voiceProfileReady = ttsConfig != null && voiceProfile != null
				&& ttsConfig.getId().equals(voiceProfile.getTtsConfigId())
				&& !Boolean.FALSE.equals(voiceProfile.getEnabled());
		if (ttsModel != null && ttsConfig == null) {
			missingItems.add("缺 TTS 能力配置");
		}
		if (ttsConfig != null && !voiceProfileReady) {
			missingItems.add("TTS 缺音色");
		}
		return new CascadeReadiness(chatReady, asrReady, ttsReady, voiceProfileReady);
	}

	/**
	 * 级联（CHAT + ASR + TTS）模式下各能力的就绪标记。
	 */
	private record CascadeReadiness(boolean chatReady, boolean asrReady, boolean ttsReady, boolean voiceProfileReady) {

		private static CascadeReadiness notReady() {
			return new CascadeReadiness(false, false, false, false);
		}

	}

	/**
	 * 解析生效配置：优先按配置 ID 精确取，否则回退到该 Agent（或全局）的默认配置。
	 */
	@Override
	public RealtimeVoiceConfigDTO resolveEffectiveConfig(Long agentId, Long realtimeConfigId) {
		return toDTO(resolveConfig(agentId, realtimeConfigId));
	}

	private RealtimeVoiceConfig resolveConfig(Long agentId, Long realtimeConfigId) {
		String tenantId = requireCurrentTenantId();
		if (realtimeConfigId != null) {
			RealtimeVoiceConfig config = realtimeVoiceConfigMapper.findById(realtimeConfigId);
			if (config == null || !tenantId.equals(config.getTenantId())) {
				return null;
			}
			return config;
		}
		return realtimeVoiceConfigMapper.findDefault(tenantId, agentId);
	}

	private RealtimeVoiceConfig findScopedDefault(Long agentId) {
		return realtimeVoiceConfigMapper.findByAgentId(requireCurrentTenantId(), agentId)
			.stream()
			.filter(item -> Boolean.TRUE.equals(item.getIsDefault()))
			.findFirst()
			.orElse(null);
	}

	private ModelAsrConfigDTO toDTO(ModelAsrConfig entity) {
		if (entity == null) {
			return ModelAsrConfigDTO.builder().build();
		}
		return ModelAsrConfigDTO.builder()
			.id(entity.getId())
			.modelConfigId(entity.getModelConfigId())
			.transcriptionPath(entity.getTranscriptionPath())
			.transcriptionStreamPath(entity.getTranscriptionStreamPath())
			.asrProtocol(entity.getAsrProtocol())
			.streamingEnabled(entity.getStreamingEnabled())
			.defaultAudioFormat(entity.getDefaultAudioFormat())
			.defaultSampleRate(entity.getDefaultSampleRate())
			.vadSupported(entity.getVadSupported())
			.hotwordSupported(entity.getHotwordSupported())
			.options(jsonSupport.readObject(entity.getOptions()))
			.build();
	}

	private ModelAsrConfig toEntity(ModelAsrConfigDTO dto, Long modelConfigId) {
		ModelAsrConfig entity = new ModelAsrConfig();
		entity.setModelConfigId(modelConfigId);
		entity.setTranscriptionPath(trimToNull(dto == null ? null : dto.getTranscriptionPath()));
		entity.setTranscriptionStreamPath(trimToNull(dto == null ? null : dto.getTranscriptionStreamPath()));
		entity.setAsrProtocol(firstText(dto == null ? null : dto.getAsrProtocol(), "http"));
		entity.setStreamingEnabled(Boolean.TRUE.equals(dto == null ? null : dto.getStreamingEnabled()));
		entity.setDefaultAudioFormat(firstText(dto == null ? null : dto.getDefaultAudioFormat(), "webm"));
		entity.setDefaultSampleRate(dto == null || dto.getDefaultSampleRate() == null ? 16000 : dto.getDefaultSampleRate());
		entity.setVadSupported(!Boolean.FALSE.equals(dto == null ? null : dto.getVadSupported()));
		entity.setHotwordSupported(Boolean.TRUE.equals(dto == null ? null : dto.getHotwordSupported()));
		entity.setOptions(jsonSupport.writeObject(dto == null ? null : dto.getOptions()));
		entity.setDeleted(false);
		return entity;
	}

	private ModelRealtimeVoiceConfigDTO toDTO(ModelRealtimeVoiceConfig entity) {
		if (entity == null) {
			return ModelRealtimeVoiceConfigDTO.builder().build();
		}
		return ModelRealtimeVoiceConfigDTO.builder()
			.id(entity.getId())
			.modelConfigId(entity.getModelConfigId())
			.websocketUrl(entity.getWebsocketUrl())
			.webrtcUrl(entity.getWebrtcUrl())
			.inputAudioFormat(entity.getInputAudioFormat())
			.outputAudioFormat(entity.getOutputAudioFormat())
			.inputSampleRate(entity.getInputSampleRate())
			.outputSampleRate(entity.getOutputSampleRate())
			.turnDetectionType(entity.getTurnDetectionType())
			.voiceName(entity.getVoiceName())
			.transcriptEnabled(entity.getTranscriptEnabled())
			.toolCallEnabled(entity.getToolCallEnabled())
			.interruptSupported(entity.getInterruptSupported())
			.options(jsonSupport.readObject(entity.getOptions()))
			.build();
	}

	private ModelRealtimeVoiceConfig toEntity(ModelRealtimeVoiceConfigDTO dto, Long modelConfigId) {
		ModelRealtimeVoiceConfig entity = new ModelRealtimeVoiceConfig();
		entity.setModelConfigId(modelConfigId);
		entity.setWebsocketUrl(trimToNull(dto == null ? null : dto.getWebsocketUrl()));
		entity.setWebrtcUrl(trimToNull(dto == null ? null : dto.getWebrtcUrl()));
		entity.setInputAudioFormat(firstText(dto == null ? null : dto.getInputAudioFormat(), DEFAULT_AUDIO_FORMAT));
		entity.setOutputAudioFormat(firstText(dto == null ? null : dto.getOutputAudioFormat(), DEFAULT_AUDIO_FORMAT));
		entity.setInputSampleRate(dto == null || dto.getInputSampleRate() == null ? 16000 : dto.getInputSampleRate());
		entity.setOutputSampleRate(dto == null || dto.getOutputSampleRate() == null ? 24000 : dto.getOutputSampleRate());
		entity.setTurnDetectionType(firstText(dto == null ? null : dto.getTurnDetectionType(), "server_vad"));
		entity.setVoiceName(trimToNull(dto == null ? null : dto.getVoiceName()));
		entity.setTranscriptEnabled(!Boolean.FALSE.equals(dto == null ? null : dto.getTranscriptEnabled()));
		entity.setToolCallEnabled(!Boolean.FALSE.equals(dto == null ? null : dto.getToolCallEnabled()));
		entity.setInterruptSupported(!Boolean.FALSE.equals(dto == null ? null : dto.getInterruptSupported()));
		entity.setOptions(jsonSupport.writeObject(dto == null ? null : dto.getOptions()));
		entity.setDeleted(false);
		return entity;
	}

	private RealtimeVoiceConfigDTO toDTO(RealtimeVoiceConfig entity) {
		if (entity == null) {
			return null;
		}
		return RealtimeVoiceConfigDTO.builder()
			.id(entity.getId())
			.agentId(entity.getAgentId())
			.configName(entity.getConfigName())
			.runtimeMode(entity.getRuntimeMode())
			.transport(entity.getTransport())
			.chatModelConfigId(entity.getChatModelConfigId())
			.asrModelConfigId(entity.getAsrModelConfigId())
			.ttsModelConfigId(entity.getTtsModelConfigId())
			.realtimeVoiceModelConfigId(entity.getRealtimeVoiceModelConfigId())
			.voiceProfileId(entity.getVoiceProfileId())
			.inputAudioFormat(entity.getInputAudioFormat())
			.inputSampleRate(entity.getInputSampleRate())
			.outputAudioFormat(entity.getOutputAudioFormat())
			.outputSampleRate(entity.getOutputSampleRate())
			.vadEnabled(entity.getVadEnabled())
			.allowInterrupt(entity.getAllowInterrupt())
			.partialTranscriptEnabled(entity.getPartialTranscriptEnabled())
			.textRecordEnabled(entity.getTextRecordEnabled())
			.fallbackEnabled(entity.getFallbackEnabled())
			.fallbackStrategy(entity.getFallbackStrategy())
			.connectTimeoutMs(entity.getConnectTimeoutMs())
			.readTimeoutMs(entity.getReadTimeoutMs())
			.idleTimeoutMs(entity.getIdleTimeoutMs())
			.maxTurnDurationMs(entity.getMaxTurnDurationMs())
			.heartbeatIntervalMs(entity.getHeartbeatIntervalMs())
			.isDefault(entity.getIsDefault())
			.enabled(entity.getEnabled())
			.options(jsonSupport.readObject(entity.getOptions()))
			.build();
	}

	private RealtimeVoiceConfig toEntity(RealtimeVoiceConfigDTO dto) {
		RealtimeVoiceConfig entity = new RealtimeVoiceConfig();
		entity.setId(dto == null ? null : dto.getId());
		entity.setAgentId(dto == null ? null : dto.getAgentId());
		entity.setConfigName(firstText(dto == null ? null : dto.getConfigName(), "默认实时语音"));
		entity.setRuntimeMode(RealtimeVoiceRuntimeMode.fromCode(dto == null ? null : dto.getRuntimeMode()).getCode());
		entity.setTransport(TransportType.fromCode(firstText(dto == null ? null : dto.getTransport(),
				TransportType.WEBSOCKET.getCode())).getCode());
		entity.setChatModelConfigId(dto == null ? null : dto.getChatModelConfigId());
		entity.setAsrModelConfigId(dto == null ? null : dto.getAsrModelConfigId());
		entity.setTtsModelConfigId(dto == null ? null : dto.getTtsModelConfigId());
		entity.setRealtimeVoiceModelConfigId(dto == null ? null : dto.getRealtimeVoiceModelConfigId());
		entity.setVoiceProfileId(dto == null ? null : dto.getVoiceProfileId());
		entity.setInputAudioFormat(firstText(dto == null ? null : dto.getInputAudioFormat(), DEFAULT_AUDIO_FORMAT));
		entity.setInputSampleRate(dto == null || dto.getInputSampleRate() == null ? 16000 : dto.getInputSampleRate());
		entity.setOutputAudioFormat(firstText(dto == null ? null : dto.getOutputAudioFormat(), DEFAULT_AUDIO_FORMAT));
		entity.setOutputSampleRate(dto == null || dto.getOutputSampleRate() == null ? 24000 : dto.getOutputSampleRate());
		entity.setVadEnabled(!Boolean.FALSE.equals(dto == null ? null : dto.getVadEnabled()));
		entity.setAllowInterrupt(!Boolean.FALSE.equals(dto == null ? null : dto.getAllowInterrupt()));
		entity.setPartialTranscriptEnabled(!Boolean.FALSE.equals(dto == null ? null : dto.getPartialTranscriptEnabled()));
		entity.setTextRecordEnabled(!Boolean.FALSE.equals(dto == null ? null : dto.getTextRecordEnabled()));
		entity.setFallbackEnabled(!Boolean.FALSE.equals(dto == null ? null : dto.getFallbackEnabled()));
		entity.setFallbackStrategy(firstText(dto == null ? null : dto.getFallbackStrategy(), "RECORDING_ASR"));
		entity.setConnectTimeoutMs(dto == null || dto.getConnectTimeoutMs() == null ? 10000 : dto.getConnectTimeoutMs());
		entity.setReadTimeoutMs(dto == null || dto.getReadTimeoutMs() == null ? 60000 : dto.getReadTimeoutMs());
		entity.setIdleTimeoutMs(dto == null || dto.getIdleTimeoutMs() == null ? 60000 : dto.getIdleTimeoutMs());
		entity.setMaxTurnDurationMs(dto == null || dto.getMaxTurnDurationMs() == null ? 60000
				: dto.getMaxTurnDurationMs());
		entity.setHeartbeatIntervalMs(dto == null || dto.getHeartbeatIntervalMs() == null ? 15000
				: dto.getHeartbeatIntervalMs());
		entity.setIsDefault(!Boolean.FALSE.equals(dto == null ? null : dto.getIsDefault()));
		entity.setEnabled(!Boolean.FALSE.equals(dto == null ? null : dto.getEnabled()));
		entity.setOptions(jsonSupport.writeObject(dto == null ? null : dto.getOptions()));
		entity.setDeleted(false);
		return entity;
	}

	private void validateConfig(RealtimeVoiceConfig entity) {
		validateSampleRate(entity.getInputSampleRate());
		validateSampleRate(entity.getOutputSampleRate());
	}

	private void validateSampleRate(Integer sampleRate) {
		if (sampleRate != null && !SUPPORTED_SAMPLE_RATES.contains(sampleRate)) {
			throw badRequest(RealtimeVoiceErrorDict.AUDIO_FORMAT_INVALID);
		}
	}

	private Long activeModelId(ModelType modelType) {
		ModelConfigDTO active = modelConfigDataService.getActiveConfigByType(modelType);
		return active == null ? null : active.getId();
	}

	private ModelConfigDTO resolveModelConfig(Long modelConfigId, ModelType modelType) {
		if (modelConfigId != null) {
			try {
				return modelConfigDataService.getConfigById(modelConfigId, modelType);
			}
			catch (Exception ex) {
				// 指定的模型配置取不到就静默当作未配置，会掩盖 id 写错或类型不匹配
				log.warn("Unable to resolve the requested model config, treating it as absent. modelConfigId={}, "
						+ "modelType={}", modelConfigId, modelType, ex);
				return null;
			}
		}
		return modelConfigDataService.getActiveConfigByType(modelType);
	}

	private boolean modelReady(ModelConfigDTO modelConfig, ModelType modelType) {
		if (modelConfig == null) {
			return false;
		}
		if (!modelType.getCode().equalsIgnoreCase(modelConfig.getModelType())) {
			return false;
		}
		if (!StringUtils.hasText(modelConfig.getBaseUrl()) || !StringUtils.hasText(modelConfig.getModelName())) {
			return false;
		}
		if (!isCustomProvider(modelConfig) && !Boolean.TRUE.equals(modelConfig.getApiKeyConfigured())) {
			return false;
		}
		return true;
	}

	private boolean isProviderKeyMissing(ModelConfigDTO modelConfig) {
		return modelConfig != null && !isCustomProvider(modelConfig) && !Boolean.TRUE.equals(modelConfig.getApiKeyConfigured());
	}

	private boolean isCustomProvider(ModelConfigDTO modelConfig) {
		return modelConfig != null && "custom".equalsIgnoreCase(modelConfig.getProvider());
	}

	private boolean isWsTransport(RealtimeVoiceConfig config) {
		return TransportType.WEBSOCKET.getCode()
			.equalsIgnoreCase(firstText(config == null ? null : config.getTransport(), TransportType.WEBSOCKET.getCode()));
	}

	private Long firstId(Long value, Long fallback) {
		return value == null ? fallback : value;
	}

	private TtsVoiceProfile resolveVoiceProfile(ModelTtsConfig ttsConfig, Long voiceProfileId) {
		if (ttsConfig == null) {
			return null;
		}
		return voiceProfileId == null ? voiceProfileMapper.findDefaultByTtsConfigId(ttsConfig.getId())
				: voiceProfileMapper.findById(voiceProfileId);
	}

	private String firstText(String value, String fallback) {
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

	private String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private CheckedException badRequest(RealtimeVoiceErrorDict dict) {
		return CheckedException.badRequest(dict.getValue(), dict.getLabel());
	}

	private String requireCurrentTenantId() {
		return platformScopePermissionService.requireCurrentTenantId(REALTIME_VOICE_RESOURCE);
	}

}
