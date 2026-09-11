/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.service.realtime;

import com.sn68.agent.dataagent.dto.tts.RealtimeSignalReq;
import com.sn68.agent.dataagent.dto.tts.RealtimeSignalResp;
import com.sn68.agent.dataagent.dto.tts.RealtimeVoiceSessionReq;
import com.sn68.agent.dataagent.dto.tts.RealtimeVoiceSessionVO;
import com.sn68.agent.dataagent.entity.RealtimeVoiceSession;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperService;

/**
 * 实时语音会话服务契约。
 */
public interface RealtimeVoiceSessionService extends SuperService<RealtimeVoiceSession> {

	/**
	 * 创建实时语音会话。
	 */
	RealtimeVoiceSessionVO create(RealtimeVoiceSessionReq request);

	/**
	 * 处理实时语音会话。
	 */
	RealtimeSignalResp offer(Long id, RealtimeSignalReq request);

	/**
	 * 处理实时语音会话。
	 */
	RealtimeSignalResp iceCandidates(Long id, RealtimeSignalReq request);

	/**
	 * 处理实时语音会话。
	 */
	RealtimeVoiceSessionVO interrupt(Long id);

	/**
	 * 删除实时语音会话。
	 */
	void delete(Long id);

	/**
	 * 校验实时语音会话。
	 */
	RealtimeVoiceSession validateWebSocketToken(String sessionId, String token);

	/**
	 * 处理实时语音会话。
	 */
	RealtimeVoiceSessionVO markActive(String sessionId);

	/**
	 * 保存实时语音会话。
	 */
	RealtimeVoiceSessionVO updateTurn(String sessionId, String turnId, Long sequence, String runtimeRequestId);

	/**
	 * 处理实时语音会话。
	 */
	RealtimeVoiceSessionVO markFailed(String sessionId, Integer errorCode, String errorMessage);

	/**
	 * 处理实时语音会话。
	 */
	void closeBySessionId(String sessionId);

}
