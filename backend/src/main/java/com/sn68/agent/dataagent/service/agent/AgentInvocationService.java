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
package com.sn68.agent.dataagent.service.agent;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;

/**
 * AgentInvocation服务契约。
 */
public interface AgentInvocationService {

	/**
	 * 处理AgentInvocation。
	 */
	String invoke(AgentRequest request);

	/**
	 * 同步调用并返回本次运行产生的公开 FLOW UI 消息，供文本 IM 适配层使用。
	 */
	AgentInvocationResult invokeDetailed(AgentRequest request);

}
