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
package com.sn68.agent.dataagent.agentscope.tool.semantic;

import lombok.extern.slf4j.Slf4j;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.ToolContextRequestResolver;
import com.sn68.agent.dataagent.agentscope.tool.ToolError;
import com.sn68.agent.dataagent.agentscope.tool.ToolErrorCode;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 语义模型检索工具的执行逻辑：解析入参并调用检索服务，命中结果序列化为工具返回，
 * 失败时输出结构化 ToolError 而非抛出异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticModelToolSupport {

	public static final String INPUT_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "query": {
			      "type": "string",
			      "description": "必填。用于补充理解表/列含义的关键词、字段名、别名或枚举说明。"
			    },
			    "tableNames": {
			      "type": "array",
			      "description": "可选。将检索范围限制在这些表内；如果数据源探索工具已能定位表结构，则不必传该工具。",
			      "items": {
			        "type": "string"
			      }
			    }
			  },
			  "required": ["query"]
			}
			""";

	private final ObjectMapper objectMapper;

	private final SemanticModelSearchService semanticModelSearchService;

	public ToolCallback createSearchToolCallback(SkillVersionResources resources, String toolName, String description) {
		ToolDefinition toolDefinition = ToolDefinition.builder()
			.name(toolName)
			.description(description)
			.inputSchema(INPUT_SCHEMA)
			.build();
		return new SemanticModelSearchToolCallback(toolDefinition, objectMapper, semanticModelSearchService, resources);
	}

	private static final class SemanticModelSearchToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition;

		private final ObjectMapper objectMapper;

		private final SemanticModelSearchService semanticModelSearchService;

		private final SkillVersionResources resources;

		private SemanticModelSearchToolCallback(ToolDefinition toolDefinition, ObjectMapper objectMapper,
				SemanticModelSearchService semanticModelSearchService, SkillVersionResources resources) {
			this.toolDefinition = toolDefinition;
			this.objectMapper = objectMapper;
			this.semanticModelSearchService = semanticModelSearchService;
			this.resources = resources;
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return toolDefinition;
		}

		@Override
		public String call(String toolInput) {
			return call(toolInput, null);
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			try {
				SemanticModelSearchRequest request = StringUtils.hasText(toolInput)
						? objectMapper.readValue(toolInput, SemanticModelSearchRequest.class)
						: new SemanticModelSearchRequest();
				validateRequest(request);
				AgentRequest agentRequest = ToolContextRequestResolver.resolveGraphRequest(toolContext);
				return objectMapper
					.writeValueAsString(semanticModelSearchService.search(request, resources, agentRequest));
			}
			catch (Exception ex) {
				throw new IllegalStateException(objectToJson(
						ToolError.of(ToolErrorCode.EXECUTION_FAILED, "语义模型检索工具执行失败：" + ex.getMessage())),
						ex);
			}
		}

		private void validateRequest(SemanticModelSearchRequest request) {
			if (request == null || !StringUtils.hasText(request.getQuery())) {
				throw new IllegalArgumentException(
						objectToJson(ToolError.of(ToolErrorCode.INVALID_INPUT, "语义模型检索工具需要 query 参数")));
			}
		}

		private String objectToJson(Object value) {
			try {
				return objectMapper.writeValueAsString(value);
			}
			catch (Exception ex) {
				// The real cause never reaches the model or the logs otherwise; only this generic frame does.
				log.warn("Failed to serialize the tool error payload, returning a generic failure frame. valueType={}",
						value == null ? null : value.getClass().getName(), ex);
				return "{\"code\":\"EXECUTION_FAILED\",\"message\":\"工具错误序列化失败\"}";
			}
		}

	}

}
