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
package com.sn68.agent.dataagent.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 出站上下文采集过滤器：请求进入时快照需要向下游透传的请求头。
 */
@Order(-999998)
@Component
public class DataAgentOutboundContextFilter extends OncePerRequestFilter {

	private static final List<String> DATA_AGENT_PATH_PREFIXES = List.of(
			"/data-agent",
			"/chat",
			"/stream",
			"/agent-knowledge",
			"/agent-interactions",
			"/audio",
			"/datasource",
			"/im-callbacks",
			"/im-connectors",
			"/im-conversation-bindings",
			"/im-messages",
			"/im-provider-configs",
			"/im-setup",
			"/im-user-identities",
			"/mcp-exposures",
			"/model-config",
			"/notification-authorizations",
			"/notification-connectors",
			"/notification-targets",
			"/notification-templates",
			"/notifications",
			"/permissions",
			"/realtime-voice",
			"/runtime-hooks",
			"/digital-employees",
			"/skills",
			"/tools");

	private static final List<String> LEGACY_PATH_PREFIXES = List.of("/agent", "/chat/session/list", "/prompt-template");

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getServletPath();
		if (!StringUtils.hasText(path)) {
			path = request.getRequestURI();
		}
		if (path == null || matchesAny(path, LEGACY_PATH_PREFIXES)) {
			return true;
		}
		return !matchesAny(path, DATA_AGENT_PATH_PREFIXES);
	}

	private static boolean matchesAny(String path, List<String> prefixes) {
		return prefixes.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		try {
			DataAgentOutboundContext.set(DataAgentOutboundContext.captureFrom(request));
			filterChain.doFilter(request, response);
		}
		finally {
			DataAgentOutboundContext.clear();
		}
	}

}
