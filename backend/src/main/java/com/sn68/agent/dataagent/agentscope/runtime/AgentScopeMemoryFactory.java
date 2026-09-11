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
package com.sn68.agent.dataagent.agentscope.runtime;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.session.AgentScopeNativeSessionService;
import io.agentscope.core.memory.InMemoryMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AgentScopeMemory组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeMemoryFactory {

	private final AgentScopeNativeSessionService nativeSessionService;

	/**
	 * 创建AgentScopeMemory。
	 */
	public PreparedMemory create(AgentRequest request) {
		InMemoryMemory memory = new InMemoryMemory();
		String threadId = request == null ? null : request.getThreadId();
		if (!StringUtils.hasText(threadId)) {
			return new PreparedMemory(memory, false);
		}
		boolean loadedFromNative = nativeSessionService.loadMemoryIfExists(memory, threadId,
				request.getUserIdSnapshot());
		if (loadedFromNative) {
			log.debug("Loaded AgentScope native session memory, threadId={}", threadId);
			return new PreparedMemory(memory, true);
		}
		log.debug("No AgentScope native session memory found, threadId={}", threadId);
		return new PreparedMemory(memory, false);
	}

}
