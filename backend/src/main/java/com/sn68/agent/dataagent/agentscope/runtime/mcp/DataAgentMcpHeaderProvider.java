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
package com.sn68.agent.dataagent.agentscope.runtime.mcp;

import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * DataAgentMcpHeaderProvider组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Component
public class DataAgentMcpHeaderProvider {

	/**
	 * 处理DataAgentMcpHeaderProvider。
	 */
	public Map<String, String> userHeaders() {
		return sanitize(DataAgentOutboundContext.get().headers());
	}

	/**
	 * 处理DataAgentMcpHeaderProvider。
	 */
	public Map<String, String> systemHeaders() {
		return Map.of();
	}

	/**
	 * 处理DataAgentMcpHeaderProvider。
	 */
	public Map<String, String> effectiveHeaders() {
		Map<String, String> headers = new LinkedHashMap<>(userHeaders());
		if (!containsUserAuthorization(headers)) {
			headers.putAll(systemHeaders());
		}
		return headers;
	}

	private boolean containsUserAuthorization(Map<String, String> headers) {
		return headers != null
				&& (StringUtils.hasText(headers.get("V4-Authorization"))
						|| StringUtils.hasText(headers.get("Authorization")));
	}

	private Map<String, String> sanitize(Map<String, String> source) {
		if (source == null || source.isEmpty()) {
			return Map.of();
		}
		Map<String, String> headers = new LinkedHashMap<>();
		source.forEach((key, value) -> {
			if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
				headers.put(key, value);
			}
		});
		return headers.isEmpty() ? Map.of() : headers;
	}

}
