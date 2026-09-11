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
package com.sn68.agent.dataagent.tool;

import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import java.util.Map;

/**
 * 执行资源Invoker服务契约。
 */
public interface ToolTransportInvoker {

	/**
	 * 处理执行资源Invoker。
	 */
	Map<String, Object> invoke(String resourceKey, Map<String, Object> arguments);

	/**
	 * Invoke an immutable published resource snapshot while still honoring the current
	 * resource enable/disable switch.
	 */
	Map<String, Object> invoke(AgentExecutionResource resourceSnapshot, Map<String, Object> arguments);

}
