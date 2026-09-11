/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.service.DataAgentService;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.ModelRealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechReq;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechResult;
import com.sn68.agent.dataagent.entity.ModelRealtimeVoiceConfig;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.entity.RealtimeVoiceSession;
import com.sn68.agent.dataagent.enums.AgentRequestSourceDict;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.enums.RealtimeVoiceErrorDict;
import com.sn68.agent.dataagent.enums.RealtimeVoiceRuntimeMode;
import com.sn68.agent.dataagent.repository.ModelRealtimeVoiceConfigMapper;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.audio.AudioTranscriptionService;
import com.sn68.agent.dataagent.service.chat.ChatMessageService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.dataagent.service.realtime.client.RealtimeVoiceEventSink;
import com.sn68.agent.dataagent.service.realtime.client.RealtimeVoiceProviderGateway;
import com.sn68.agent.dataagent.service.realtime.client.RealtimeVoiceProviderRequest;
import com.sn68.agent.dataagent.service.realtime.client.RealtimeVoiceProviderSession;
import com.sn68.agent.dataagent.service.realtime.RealtimeVoiceSessionStore.RealtimeAudioChunk;
import com.sn68.agent.dataagent.service.tts.TextToSpeechService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 实时语音组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeVoiceOrchestrator {

	private final RealtimeVoiceSessionStore sessionStore;

	private final RealtimeVoiceConfigService configService;

	private final RealtimeVoiceSessionService sessionService;

	private final ModelConfigDataService modelConfigDataService;

	private final ModelRealtimeVoiceConfigMapper realtimeVoiceModelConfigMapper;

	private final List<RealtimeVoiceProviderGateway> realtimeVoiceProviderGateways;

	private final AudioTranscriptionService audioTranscriptionService;

	private final DataAgentService dataAgentService;

	private final TextToSpeechService textToSpeechService;

	private final ChatMessageService chatMessageService;

	private final DataChatSessionService chatSessionService;

	private final ObjectMapper objectMapper;

	private final Map<String, RealtimeVoiceProviderSession> providerSessions = new ConcurrentHashMap<>();

	/**
	 * 校验实时语音。
	 */
	public boolean isRealtime(RealtimeVoiceSession session) {
		return session != null && RealtimeVoiceRuntimeMode.REALTIME.getCode().equalsIgnoreCase(session.getRuntimeMode());
	}

	/**
	 * 执行实时语音。
	 */
	public void openRealtime(RealtimeVoiceSession session, RealtimeVoiceEventSink eventSink) {
		if (!isRealtime(session)) {
			return;
		}
		RealtimeVoiceConfigDTO config = configService.resolveEffectiveConfig(session.getAgentId(),
				session.getRealtimeConfigId());
		if (config == null || session.getRealtimeVoiceModelConfigId() == null) {
			throw badRequest(RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY);
		}
		ModelConfigDTO modelConfig = modelConfigDataService.getRuntimeConfigById(session.getRealtimeVoiceModelConfigId(),
				ModelType.REALTIME_VOICE);
		ModelRealtimeVoiceConfig modelRealtimeConfig = realtimeVoiceModelConfigMapper
			.findByModelConfigId(session.getRealtimeVoiceModelConfigId());
		if (modelRealtimeConfig == null) {
			throw badRequest(RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY);
		}
		RealtimeVoiceProviderRequest request = new RealtimeVoiceProviderRequest(modelConfig,
				toDTO(modelRealtimeConfig), config, session, eventSink);
		RealtimeVoiceProviderGateway gateway = realtimeVoiceProviderGateways.stream()
			.filter(item -> item.supports(request))
			.findFirst()
			.orElseThrow(() -> badRequest(RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY));
		RealtimeVoiceProviderSession providerSession = gateway.open(request);
		RealtimeVoiceProviderSession old = providerSessions.put(session.getSessionId(), providerSession);
		if (old != null) {
			old.close();
		}
	}

	/**
	 * 处理实时语音。
	 */
	public void appendAudio(RealtimeVoiceSession session, byte[] bytes, String contentType) {
		if (isRealtime(session)) {
			RealtimeVoiceProviderSession providerSession = providerSessions.get(session.getSessionId());
			if (providerSession == null) {
				throw badRequest(RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY);
			}
			providerSession.appendAudio(bytes);
			return;
		}
		sessionStore.appendAudio(session.getSessionId(), bytes, contentType);
	}

	/**
	 * 处理实时语音。
	 */
	public void commitRealtime(RealtimeVoiceSession session) {
		RealtimeVoiceProviderSession providerSession = providerSessions.get(session.getSessionId());
		if (providerSession == null) {
			throw badRequest(RealtimeVoiceErrorDict.REALTIME_PROVIDER_NOT_READY);
		}
		providerSession.commit();
	}

	/**
	 * 处理实时语音。
	 */
	public RealtimeVoiceTurnResult commit(RealtimeVoiceSession session, String turnId, String contentType) {
		RealtimeVoiceConfigDTO config = configService.resolveEffectiveConfig(session.getAgentId(),
				session.getRealtimeConfigId());
		if (config == null) {
			throw badRequest(RealtimeVoiceErrorDict.CONFIG_NOT_READY);
		}
		RealtimeAudioChunk chunk = sessionStore.drainAudio(session.getSessionId(), contentType);
		if (chunk.audio() == null || chunk.audio().length == 0) {
			throw badRequest(RealtimeVoiceErrorDict.AUDIO_EMPTY);
		}
		String resolvedTurnId = StringUtils.hasText(turnId) ? turnId : UUID.randomUUID().toString();
		String runtimeRequestId = UUID.randomUUID().toString();
		sessionStore.updateRuntime(session.getSessionId(), resolvedTurnId, runtimeRequestId);
		sessionService.updateTurn(session.getSessionId(), resolvedTurnId, chunk.sequence(), runtimeRequestId);

		String transcript = transcribe(session, chunk);
		if (!StringUtils.hasText(transcript)) {
			throw badRequest(RealtimeVoiceErrorDict.ASR_FAILED);
		}
		boolean textRecordEnabled = !Boolean.FALSE.equals(config.getTextRecordEnabled());
		if (textRecordEnabled) {
			saveMessage(session, "user", transcript, resolvedTurnId, chunk.sequence());
		}

		String answer = executeAgent(session, transcript, runtimeRequestId);
		if (textRecordEnabled && StringUtils.hasText(answer)) {
			saveMessage(session, "assistant", answer, resolvedTurnId, chunk.sequence());
		}
		AudioSpeechResult audio = synthesize(session, config, answer);
		return new RealtimeVoiceTurnResult(resolvedTurnId, chunk.sequence(), runtimeRequestId, transcript, answer,
				audio == null ? null : audio.audio(), audio == null ? null : audio.contentType(),
				audio == null ? null : audio.format());
	}

	/**
	 * 校验实时语音。
	 */
	public boolean cancel(RealtimeVoiceSession session) {
		sessionStore.clearAudio(session.getSessionId());
		RealtimeVoiceProviderSession providerSession = providerSessions.get(session.getSessionId());
		if (providerSession != null) {
			providerSession.cancel();
			return true;
		}
		String runtimeRequestId = firstText(sessionStore.runtimeRequestId(session.getSessionId()),
				session.getRuntimeRequestId());
		if (!Boolean.TRUE.equals(session.getAllowInterrupt()) || !StringUtils.hasText(session.getThreadId())
				|| !StringUtils.hasText(runtimeRequestId)) {
			return false;
		}
		return dataAgentService.stopStreamProcessing(session.getThreadId(), runtimeRequestId);
	}

	/**
	 * 处理实时语音。
	 */
	public void close(RealtimeVoiceSession session) {
		cancel(session);
		RealtimeVoiceProviderSession providerSession = providerSessions.remove(session.getSessionId());
		if (providerSession != null) {
			providerSession.close();
		}
		sessionStore.remove(session.getSessionId());
	}

	private ModelRealtimeVoiceConfigDTO toDTO(ModelRealtimeVoiceConfig entity) {
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
			.options(jsonOptions(entity.getOptions()))
			.build();
	}

	private Map<String, Object> jsonOptions(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(value, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			// 静默返回空 options 会让实时语音按默认参数跑，与配置不一致且无从排查
			log.warn("Unable to parse realtime voice JSON options, falling back to empty options. length={}",
					value.length(), ex);
			return Map.of();
		}
	}

	private String transcribe(RealtimeVoiceSession session, RealtimeAudioChunk chunk) {
		try {
			ByteArrayMultipartFile file = new ByteArrayMultipartFile("file", audioFileName(chunk.contentType()),
					chunk.contentType(), chunk.audio());
			return audioTranscriptionService.transcribe(file, session.getAsrModelConfigId());
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw badRequest(RealtimeVoiceErrorDict.ASR_FAILED);
		}
	}

	private String executeAgent(RealtimeVoiceSession session, String transcript, String runtimeRequestId) {
		if (session.getAgentId() == null || !StringUtils.hasText(session.getThreadId())) {
			throw badRequest(RealtimeVoiceErrorDict.AGENT_FAILED);
		}
		try {
			AgentRequest request = AgentRequest.builder()
				.agentId(String.valueOf(session.getAgentId()))
				.threadId(session.getThreadId())
				.runtimeRequestId(runtimeRequestId)
				.query(transcript)
				.chatModelConfigId(session.getChatModelConfigId())
				.responseMode("normal")
				.requestSource(AgentRequestSourceDict.WEB.getValue())
				.build();
			return dataAgentService.executeAgentOnce(request);
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw badRequest(RealtimeVoiceErrorDict.AGENT_FAILED);
		}
	}

	private AudioSpeechResult synthesize(RealtimeVoiceSession session, RealtimeVoiceConfigDTO config, String answer) {
		if (!StringUtils.hasText(answer)) {
			return null;
		}
		try {
			return textToSpeechService.synthesize(AudioSpeechReq.builder()
				.text(answer)
				.modelConfigId(session.getTtsModelConfigId())
				.voiceProfileId(session.getVoiceProfileId())
				.sampleRate(config.getOutputSampleRate())
				.build());
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw badRequest(RealtimeVoiceErrorDict.TTS_FAILED);
		}
	}

	private void saveMessage(RealtimeVoiceSession session, String role, String content, String turnId, long sequence) {
		Long chatSessionId = parseLong(session.getThreadId());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("source", "REALTIME_VOICE");
		metadata.put("turnId", turnId);
		metadata.put("sequence", sequence);
		try {
			chatMessageService.saveMessage(DataChatMessage.builder()
				.sessionId(chatSessionId)
				.role(role)
				.content(content)
				.messageType("text")
				.metadata(objectMapper.writeValueAsString(metadata))
				.build(), session.getAgentId());
			chatSessionService.updateSessionTime(chatSessionId, session.getAgentId());
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw badRequest(RealtimeVoiceErrorDict.AGENT_FAILED);
		}
	}

	private Long parseLong(String value) {
		if (!StringUtils.hasText(value)) {
			throw badRequest(RealtimeVoiceErrorDict.AGENT_FAILED);
		}
		try {
			return Long.valueOf(value);
		}
		catch (NumberFormatException ex) {
			throw badRequest(RealtimeVoiceErrorDict.AGENT_FAILED);
		}
	}

	private String audioFileName(String contentType) {
		String normalized = contentType == null ? "" : contentType.toLowerCase();
		if (normalized.contains("wav")) {
			return "realtime.wav";
		}
		if (normalized.contains("mpeg") || normalized.contains("mp3")) {
			return "realtime.mp3";
		}
		if (normalized.contains("ogg")) {
			return "realtime.ogg";
		}
		return "realtime.webm";
	}

	private String firstText(String value, String fallback) {
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

	private CheckedException badRequest(RealtimeVoiceErrorDict dict) {
		return CheckedException.badRequest(dict.getValue(), dict.getLabel());
	}

}
