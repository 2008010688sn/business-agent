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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能体运行时错误展示信息。
 *
 * <p>{@code diagnosticCode} 是仅供运维定位的内部失败码（例如路由的 {@code VECTOR_TIMEOUT}），
 * 只进日志与诊断落库，不进面向终端用户的文案与 SSE 元数据。
 */
public record AgentRuntimeError(AgentRuntimeErrorCode code, Integer httpStatus, String messageOverride,
		String diagnosticCode) {

	public static AgentRuntimeError of(AgentRuntimeErrorCode code) {
		return new AgentRuntimeError(code, null, null, null);
	}

	public static AgentRuntimeError of(AgentRuntimeErrorCode code, Integer httpStatus) {
		return new AgentRuntimeError(code, httpStatus, null, null);
	}

	public static AgentRuntimeError of(AgentRuntimeErrorCode code, String messageOverride) {
		return new AgentRuntimeError(code, null, messageOverride, null);
	}

	public static AgentRuntimeError diagnosed(AgentRuntimeErrorCode code, String diagnosticCode) {
		return new AgentRuntimeError(code, null, null, diagnosticCode);
	}

	public String message() {
		return messageOverride == null || messageOverride.isBlank() ? code.getLabel() : messageOverride;
	}

	public Map<String, Object> toMetadata(String runtimeRequestId) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("errorCode", code.getValue());
		metadata.put("messageKey", code.getValue());
		metadata.put("retryable", code.isRetryable());
		metadata.put("runtimeRequestId", runtimeRequestId);
		metadata.put("discardPartialOutput", true);
		return metadata;
	}

}
