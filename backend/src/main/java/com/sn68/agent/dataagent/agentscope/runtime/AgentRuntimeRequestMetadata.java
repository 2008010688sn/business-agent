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

/**
 * 随工具执行上下文透传的运行时请求元数据：标识本次运行的智能体、会话线程与请求，
 * 并携带人工反馈标记，供工具在无完整 AgentRequest 时还原最小请求信息。
 */
public record AgentRuntimeRequestMetadata(String agentId, String threadId, String runtimeRequestId,
		boolean humanFeedback, String humanFeedbackContent) {

}
