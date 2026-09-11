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
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.entity.RealtimeVoiceSession;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import org.springframework.stereotype.Repository;

/**
 * 实时语音会话Mapper服务契约。
 */
@Repository
public interface RealtimeVoiceSessionMapper extends SuperMapper<RealtimeVoiceSession> {

	/**
	 * 按主键查询实时语音会话；逻辑删除自动过滤。
	 */
	default RealtimeVoiceSession findById(Long id) {
		return selectOne(new LambdaQueryWrapper<RealtimeVoiceSession>().eq(RealtimeVoiceSession::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 按业务会话标识（sessionId 字符串）查询实时语音会话。
	 */
	default RealtimeVoiceSession findBySessionId(String sessionId) {
		return selectOne(new LambdaQueryWrapper<RealtimeVoiceSession>().eq(RealtimeVoiceSession::getSessionId, sessionId)
			.last(" limit 1"));
	}

}
