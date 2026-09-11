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
package com.sn68.agent.dataagent.constant;

import java.time.Duration;

/**
 * Agent 会话相关的共享默认配置常量。
 */
public final class AgentSessionConstant {

	public static final String DEFAULT_SESSION_TITLE = "新会话";

	public static final String SESSION_STATUS_ACTIVE = "active";

	public static final String SESSION_STATUS_DELETED = "deleted";

	public static final String MESSAGE_TYPE_MEMORY_TEXT = "memory-text";

	public static final String MESSAGE_TYPE_ANSWER_EXPLAIN = "answer-explain";

	public static final String MESSAGE_TYPE_SKILL_FLOW = "skill-flow";

	public static final String MESSAGE_TYPE_ANALYSIS_RESULT = "analysis-result";

	public static final String MESSAGE_TYPE_TOOL_CONFIRM = "tool-confirm";

	public static final String MESSAGE_TYPE_PERMISSION_DENIED = "permission-denied";

	public static final String AGENTSCOPE_STATE_MESSAGE_TYPE_PREFIX = "agentscope-state:";

	public static final int SESSION_TITLE_MAX_LENGTH = 20;

	public static final Duration SESSION_TITLE_GENERATION_TIMEOUT = Duration.ofSeconds(15);

	public static final Duration SESSION_EVENT_HEARTBEAT_INTERVAL = Duration.ofSeconds(2);

	private AgentSessionConstant() {

	}

}
