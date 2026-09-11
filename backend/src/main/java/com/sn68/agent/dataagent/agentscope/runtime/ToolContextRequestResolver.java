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
import io.agentscope.core.tool.ToolExecutionContext;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * 从 Spring AI ToolContext 中解析当前运行的 AgentRequest：优先取完整的 graphRequest，
 * 退化时从 AgentScope 执行上下文或请求元数据还原最小请求，供工具侧获取租户/会话信息。
 */
public final class ToolContextRequestResolver {

	private ToolContextRequestResolver() {
	}

	@Nullable
	public static AgentRequest resolveGraphRequest(@Nullable ToolContext toolContext) {
		if (toolContext == null || toolContext.getContext() == null) {
			return null;
		}
		Map<String, Object> context = toolContext.getContext();
		Object graphRequest = context.get("graphRequest");
		if (graphRequest instanceof AgentRequest request) {
			return request;
		}
		Object agentScopeContext = context.get("agentScopeContext");
		if (agentScopeContext instanceof ToolExecutionContext toolExecutionContext) {
			AgentRequest request = toolExecutionContext.get("graphRequest", AgentRequest.class);
			if (request != null) {
				return request;
			}
			AgentRequest metadataRequest = fromMetadata(toolExecutionContext.get(AgentRuntimeRequestMetadata.class));
			if (metadataRequest != null) {
				return metadataRequest;
			}
		}
		Object runtimeRequestMetadata = context.get("runtimeRequestMetadata");
		if (runtimeRequestMetadata instanceof AgentRuntimeRequestMetadata metadata) {
			return fromMetadata(metadata);
		}
		return null;
	}

	@Nullable
	private static AgentRequest fromMetadata(@Nullable AgentRuntimeRequestMetadata metadata) {
		if (metadata == null || !StringUtils.hasText(metadata.threadId())
				|| !StringUtils.hasText(metadata.runtimeRequestId())) {
			return null;
		}
		return AgentRequest.builder()
			.agentId(metadata.agentId())
			.threadId(metadata.threadId())
			.runtimeRequestId(metadata.runtimeRequestId())
			.humanFeedback(metadata.humanFeedback())
			.humanFeedbackContent(metadata.humanFeedbackContent())
			.build();
	}

}
