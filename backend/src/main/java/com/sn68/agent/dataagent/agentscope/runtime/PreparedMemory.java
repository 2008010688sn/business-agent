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

import io.agentscope.core.memory.Memory;

/**
 * 会话记忆装配结果：记忆实例本身、是否从原生会话存储加载、是否启用自动上下文压缩。
 */
public record PreparedMemory(Memory memory, boolean loadedFromNative, boolean autoContextEnabled) {

	public PreparedMemory(Memory memory, boolean loadedFromNative) {
		this(memory, loadedFromNative, false);
	}

}
