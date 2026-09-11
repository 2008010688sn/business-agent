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
package com.sn68.agent.dataagent.agentscope.tool.webfetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.ToolContextRequestResolver;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 只读网页读取工具。Bean 常驻；是否对模型可见由 Toolkit 按 {@code fetch-enabled} 过滤。
 */
@Component
@RequiredArgsConstructor
public class WebFetchToolCallback implements ToolCallback {

	private static final String DESCRIPTION = """
			读取用户给出的公开网页正文，作为回答证据。入参必须是完整 http/https 链接，字段名必须是 url。
			本系统内部链接不要调用本工具（默认只抽键，不抓页面）。不要用于 PDF、图片或需要登录的页面。
			""";

	private static final String INPUT_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "url": {
			      "type": "string",
			      "description": "必填。要读取的网页链接。"
			    }
			  },
			  "required": ["url"]
			}
			""";

	private static final ToolDefinition DEFINITION = ToolDefinition.builder()
		.name(AgentModelToolName.WEB_FETCH)
		.description(DESCRIPTION)
		.inputSchema(INPUT_SCHEMA)
		.build();

	private final WebFetchService webFetchService;

	private final ObjectMapper objectMapper;

	@Override
	public ToolDefinition getToolDefinition() {
		return DEFINITION;
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, ToolContext toolContext) {
		String url = readUrl(toolInput);
		AgentRequest request = ToolContextRequestResolver.resolveGraphRequest(toolContext);
		return webFetchService.fetch(url, request);
	}

	private String readUrl(String toolInput) {
		if (!StringUtils.hasText(toolInput)) {
			throw CheckedException.badRequest("网页读取需要 url 参数");
		}
		try {
			JsonNode node = objectMapper.readTree(toolInput);
			String url = node.path("url").asText(null);
			if (!StringUtils.hasText(url)) {
				throw CheckedException.badRequest("网页读取需要 url 参数");
			}
			return url;
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("网页读取入参不是合法 JSON 对象");
		}
	}

}
