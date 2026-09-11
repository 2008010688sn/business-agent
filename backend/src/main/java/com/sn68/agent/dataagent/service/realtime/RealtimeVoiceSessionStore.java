/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.realtime;

import com.sn68.agent.dataagent.enums.RealtimeVoiceErrorDict;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 实时语音SessionStore组件，封装 DataAgent 对应业务入口。
 */
@Component
public class RealtimeVoiceSessionStore {

	private static final int MAX_BUFFER_BYTES = 20 * 1024 * 1024;

	private final ConcurrentMap<String, SessionState> states = new ConcurrentHashMap<>();

	/**
	 * 处理实时语音SessionStore。
	 */
	public void appendAudio(String sessionId, byte[] bytes, String contentType) {
		if (!StringUtils.hasText(sessionId) || bytes == null || bytes.length == 0) {
			return;
		}
		SessionState state = state(sessionId);
		state.lock.lock();
		try {
			if (state.audioBuffer.size() + bytes.length > MAX_BUFFER_BYTES) {
				state.audioBuffer.reset();
				throw badRequest(RealtimeVoiceErrorDict.AUDIO_FORMAT_INVALID);
			}
			state.audioBuffer.writeBytes(bytes);
			if (StringUtils.hasText(contentType)) {
				state.contentType = contentType;
			}
		}
		finally {
			state.lock.unlock();
		}
	}

	/**
	 * 处理实时语音SessionStore。
	 */
	public RealtimeAudioChunk drainAudio(String sessionId, String contentType) {
		SessionState state = state(sessionId);
		state.lock.lock();
		try {
			byte[] bytes = state.audioBuffer.toByteArray();
			state.audioBuffer.reset();
			long sequence = state.sequence.incrementAndGet();
			String resolvedContentType = firstText(contentType, firstText(state.contentType, "audio/webm"));
			return new RealtimeAudioChunk(bytes, resolvedContentType, sequence);
		}
		finally {
			state.lock.unlock();
		}
	}

	/**
	 * 保存实时语音SessionStore。
	 */
	public void updateRuntime(String sessionId, String turnId, String runtimeRequestId) {
		SessionState state = state(sessionId);
		state.currentTurnId = turnId;
		state.runtimeRequestId = runtimeRequestId;
	}

	/**
	 * 执行实时语音SessionStore。
	 */
	public String runtimeRequestId(String sessionId) {
		SessionState state = states.get(sessionId);
		return state == null ? null : state.runtimeRequestId;
	}

	/**
	 * 清理实时语音SessionStore。
	 */
	public void clearAudio(String sessionId) {
		SessionState state = states.get(sessionId);
		if (state == null) {
			return;
		}
		state.lock.lock();
		try {
			state.audioBuffer.reset();
		}
		finally {
			state.lock.unlock();
		}
	}

	/**
	 * 清理实时语音SessionStore。
	 */
	public void remove(String sessionId) {
		states.remove(sessionId);
	}

	private SessionState state(String sessionId) {
		return states.computeIfAbsent(sessionId, key -> new SessionState());
	}

	private String firstText(String value, String fallback) {
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

	private CheckedException badRequest(RealtimeVoiceErrorDict dict) {
		return CheckedException.badRequest(dict.getValue(), dict.getLabel());
	}

	/**
	 * 处理实时语音SessionStore。
	 */
	public record RealtimeAudioChunk(byte[] audio, String contentType, long sequence) {
	}

	private static final class SessionState {

		private final ReentrantLock lock = new ReentrantLock();

		private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

		private final AtomicLong sequence = new AtomicLong();

		private volatile String contentType;

		private volatile String currentTurnId;

		private volatile String runtimeRequestId;

	}

}
