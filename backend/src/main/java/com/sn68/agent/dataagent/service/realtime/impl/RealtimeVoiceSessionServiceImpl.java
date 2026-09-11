/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime.impl;

import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceReadinessVO;
import com.sn68.agent.dataagent.dto.tts.RealtimeSignalReq;
import com.sn68.agent.dataagent.dto.tts.RealtimeSignalResp;
import com.sn68.agent.dataagent.dto.tts.RealtimeVoiceSessionReq;
import com.sn68.agent.dataagent.dto.tts.RealtimeVoiceSessionVO;
import com.sn68.agent.dataagent.entity.RealtimeVoiceSession;
import com.sn68.agent.dataagent.enums.RealtimeVoiceErrorDict;
import com.sn68.agent.dataagent.enums.RealtimeVoiceMode;
import com.sn68.agent.dataagent.enums.RealtimeVoiceRuntimeMode;
import com.sn68.agent.dataagent.enums.RealtimeVoiceSessionStatus;
import com.sn68.agent.dataagent.enums.TransportType;
import com.sn68.agent.dataagent.repository.RealtimeVoiceSessionMapper;
import com.sn68.agent.dataagent.service.realtime.RealtimeVoiceConfigService;
import com.sn68.agent.dataagent.service.realtime.RealtimeVoiceSessionService;
import com.sn68.agent.dataagent.service.tts.impl.TtsJsonSupport;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperServiceImpl;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 实时语音Session服务组件，封装 DataAgent 对应业务入口。
 */
@Service
@RequiredArgsConstructor
public class RealtimeVoiceSessionServiceImpl extends SuperServiceImpl<RealtimeVoiceSessionMapper, RealtimeVoiceSession>
		implements RealtimeVoiceSessionService {

	private static final int WS_TOKEN_TTL_MINUTES = 10;

	private final RealtimeVoiceConfigService configService;

	private final TtsJsonSupport jsonSupport;

	/**
	 * 创建实时语音会话：校验配置就绪后生成会话记录与 WebSocket 接入凭证。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public RealtimeVoiceSessionVO create(RealtimeVoiceSessionReq request) {
		Long agentId = request == null ? null : request.getAgentId();
		Long realtimeConfigId = request == null ? null : request.getRealtimeConfigId();
		RealtimeVoiceReadinessVO readiness = configService.readiness(agentId, realtimeConfigId);
		if (!Boolean.TRUE.equals(readiness.getReady()) && realtimeConfigId == null) {
			readiness = configService.generateDefault(agentId);
		}
		if (!Boolean.TRUE.equals(readiness.getReady()) || readiness.getConfig() == null) {
			throw badRequest(RealtimeVoiceErrorDict.CONFIG_NOT_READY);
		}

		RealtimeVoiceConfigDTO config = readiness.getConfig();
		RealtimeVoiceMode mode = RealtimeVoiceMode.fromCode(firstText(request == null ? null : request.getMode(),
				RealtimeVoiceMode.CONTINUOUS_VOICE.getCode()));
		RealtimeVoiceRuntimeMode runtimeMode = RealtimeVoiceRuntimeMode
			.fromCode(firstText(request == null ? null : request.getRuntimeMode(), config.getRuntimeMode()));
		TransportType transport = TransportType.fromCode(firstText(request == null ? null : request.getTransport(),
				firstText(config.getTransport(), TransportType.WEBSOCKET.getCode())));
		Instant now = Instant.now();
		RealtimeVoiceSession entity = new RealtimeVoiceSession();
		entity.setSessionId(UUID.randomUUID().toString());
		entity.setAgentId(agentId);
		entity.setThreadId(trimToNull(request == null ? null : request.getThreadId()));
		entity.setRealtimeConfigId(config.getId());
		entity.setRuntimeMode(runtimeMode.getCode());
		entity.setMode(mode.getCode());
		entity.setTransport(transport.getCode());
		entity.setStatus(RealtimeVoiceSessionStatus.CREATED.name());
		entity.setChatModelConfigId(firstId(request == null ? null : request.getChatModelConfigId(),
				config.getChatModelConfigId()));
		entity.setAsrModelConfigId(firstId(request == null ? null : request.getAsrModelConfigId(),
				config.getAsrModelConfigId()));
		entity.setTtsModelConfigId(firstId(request == null ? null : request.getTtsModelConfigId(),
				config.getTtsModelConfigId()));
		entity.setRealtimeVoiceModelConfigId(firstId(request == null ? null : request.getRealtimeVoiceModelConfigId(),
				config.getRealtimeVoiceModelConfigId()));
		entity.setVoiceProfileId(firstId(request == null ? null : request.getVoiceProfileId(), config.getVoiceProfileId()));
		entity.setAllowInterrupt(!Boolean.FALSE.equals(request == null ? config.getAllowInterrupt()
				: firstBoolean(request.getAllowInterrupt(), config.getAllowInterrupt())));
		entity.setVadEnabled(!Boolean.FALSE.equals(request == null ? config.getVadEnabled()
				: firstBoolean(request.getVadEnabled(), config.getVadEnabled())));
		entity.setLastSequence(0L);
		entity.setWsToken(UUID.randomUUID().toString().replace("-", ""));
		entity.setWsTokenExpiresAt(now.plus(WS_TOKEN_TTL_MINUTES, ChronoUnit.MINUTES));
		entity.setSessionOptions(jsonSupport.writeObject(request == null ? null : request.getSessionOptions()));
		entity.setStartedAt(now);
		entity.setCreateTime(now);
		entity.setLastModifyTime(now);
		entity.setDeleted(false);
		baseMapper.insert(entity);
		return toVO(entity);
	}

	/**
	 * 处理 WebRTC offer 信令：当前仅支持 WebSocket 通道，固定返回 WEBSOCKET_REQUIRED。
	 */
	@Override
	public RealtimeSignalResp offer(Long id, RealtimeSignalReq request) {
		RealtimeVoiceSession session = requireSession(id);
		return RealtimeSignalResp.builder()
			.status("WEBSOCKET_REQUIRED")
			.sessionId(session.getSessionId())
			.message("第一版实时语音使用 WebSocket /ws/ai/realtime/voice，WebRTC 后续接媒体网关。")
			.data(Map.of("transport", TransportType.WEBSOCKET.getCode()))
			.build();
	}

	/**
	 * 处理 ICE candidate 信令：当前仅支持 WebSocket 通道，固定返回 WEBSOCKET_REQUIRED。
	 */
	@Override
	public RealtimeSignalResp iceCandidates(Long id, RealtimeSignalReq request) {
		RealtimeVoiceSession session = requireSession(id);
		return RealtimeSignalResp.builder()
			.status("WEBSOCKET_REQUIRED")
			.sessionId(session.getSessionId())
			.message("第一版实时语音使用 WebSocket，不处理 ICE candidate。")
			.data(Map.of("transport", TransportType.WEBSOCKET.getCode()))
			.build();
	}

	/**
	 * 打断当前语音轮次：会话须允许打断，事务内更新会话打断标记。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public RealtimeVoiceSessionVO interrupt(Long id) {
		RealtimeVoiceSession session = requireSession(id);
		if (!Boolean.TRUE.equals(session.getAllowInterrupt())) {
			throw badRequest(RealtimeVoiceErrorDict.INTERRUPT_FAILED);
		}
		session.setStatus(RealtimeVoiceSessionStatus.INTERRUPTED.name());
		session.setLastModifyTime(Instant.now());
		baseMapper.updateById(session);
		return toVO(session);
	}

	/**
	 * 删除会话：先关闭在线通道再逻辑删除会话记录。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		RealtimeVoiceSession session = requireSession(id);
		close(session);
	}

	/**
	 * 校验 WebSocket 接入凭证：会话须存在、token 匹配且未过期，否则抛业务异常。
	 */
	@Override
	public RealtimeVoiceSession validateWebSocketToken(String sessionId, String token) {
		if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(token)) {
			throw badRequest(RealtimeVoiceErrorDict.SESSION_TOKEN_INVALID);
		}
		RealtimeVoiceSession session = baseMapper.findBySessionId(sessionId);
		if (session == null || RealtimeVoiceSessionStatus.ENDED.name().equalsIgnoreCase(session.getStatus())
				|| Boolean.TRUE.equals(session.getDeleted())) {
			throw badRequest(RealtimeVoiceErrorDict.SESSION_NOT_FOUND);
		}
		if (!token.equals(session.getWsToken()) || session.getWsTokenExpiresAt() == null
				|| Instant.now().isAfter(session.getWsTokenExpiresAt())) {
			throw badRequest(RealtimeVoiceErrorDict.SESSION_TOKEN_INVALID);
		}
		return session;
	}

	/**
	 * 将会话标记为 ACTIVE（通道建立成功后调用）。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public RealtimeVoiceSessionVO markActive(String sessionId) {
		RealtimeVoiceSession session = requireSession(sessionId);
		session.setStatus(RealtimeVoiceSessionStatus.ACTIVE.name());
		session.setLastModifyTime(Instant.now());
		baseMapper.updateById(session);
		return toVO(session);
	}

	/**
	 * 更新会话当前轮次信息（轮次 ID、序号与运行时请求 ID）。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public RealtimeVoiceSessionVO updateTurn(String sessionId, String turnId, Long sequence, String runtimeRequestId) {
		RealtimeVoiceSession session = requireSession(sessionId);
		session.setCurrentTurnId(turnId);
		session.setLastSequence(sequence == null ? session.getLastSequence() : sequence);
		session.setRuntimeRequestId(runtimeRequestId);
		session.setLastModifyTime(Instant.now());
		baseMapper.updateById(session);
		return toVO(session);
	}

	/**
	 * 将会话标记为 FAILED 并记录错误码与错误信息。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public RealtimeVoiceSessionVO markFailed(String sessionId, Integer errorCode, String errorMessage) {
		RealtimeVoiceSession session = requireSession(sessionId);
		session.setStatus(RealtimeVoiceSessionStatus.FAILED.name());
		session.setErrorCode(errorCode);
		session.setErrorMessage(errorMessage);
		session.setLastModifyTime(Instant.now());
		baseMapper.updateById(session);
		return toVO(session);
	}

	/**
	 * 按会话业务 ID 关闭会话并释放通道资源。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void closeBySessionId(String sessionId) {
		close(requireSession(sessionId));
	}

	private void close(RealtimeVoiceSession session) {
		Instant now = Instant.now();
		session.setStatus(RealtimeVoiceSessionStatus.ENDED.name());
		session.setEndedAt(now);
		session.setDeleted(true);
		session.setLastModifyTime(now);
		baseMapper.updateById(session);
	}

	private RealtimeVoiceSession requireSession(Long id) {
		if (id == null) {
			throw badRequest(RealtimeVoiceErrorDict.SESSION_NOT_FOUND);
		}
		RealtimeVoiceSession session = baseMapper.findById(id);
		if (session == null) {
			throw badRequest(RealtimeVoiceErrorDict.SESSION_NOT_FOUND);
		}
		return session;
	}

	private RealtimeVoiceSession requireSession(String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			throw badRequest(RealtimeVoiceErrorDict.SESSION_NOT_FOUND);
		}
		RealtimeVoiceSession session = baseMapper.findBySessionId(sessionId);
		if (session == null) {
			throw badRequest(RealtimeVoiceErrorDict.SESSION_NOT_FOUND);
		}
		return session;
	}

	private RealtimeVoiceSessionVO toVO(RealtimeVoiceSession entity) {
		return RealtimeVoiceSessionVO.builder()
			.id(entity.getId())
			.sessionId(entity.getSessionId())
			.agentId(entity.getAgentId())
			.threadId(entity.getThreadId())
			.realtimeConfigId(entity.getRealtimeConfigId())
			.runtimeMode(entity.getRuntimeMode())
			.mode(entity.getMode())
			.transport(entity.getTransport())
			.status(entity.getStatus())
			.chatModelConfigId(entity.getChatModelConfigId())
			.asrModelConfigId(entity.getAsrModelConfigId())
			.ttsModelConfigId(entity.getTtsModelConfigId())
			.realtimeVoiceModelConfigId(entity.getRealtimeVoiceModelConfigId())
			.voiceProfileId(entity.getVoiceProfileId())
			.allowInterrupt(entity.getAllowInterrupt())
			.vadEnabled(entity.getVadEnabled())
			.currentTurnId(entity.getCurrentTurnId())
			.lastSequence(entity.getLastSequence())
			.runtimeRequestId(entity.getRuntimeRequestId())
			.wsToken(entity.getWsToken())
			.wsTokenExpiresAt(entity.getWsTokenExpiresAt())
			.sessionOptions(jsonSupport.readObject(entity.getSessionOptions()))
			.startedAt(entity.getStartedAt())
			.endedAt(entity.getEndedAt())
			.errorCode(entity.getErrorCode())
			.errorMessage(entity.getErrorMessage())
			.build();
	}

	private Long firstId(Long value, Long fallback) {
		return value == null ? fallback : value;
	}

	private Boolean firstBoolean(Boolean value, Boolean fallback) {
		return value == null ? fallback : value;
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

}
