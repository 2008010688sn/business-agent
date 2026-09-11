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
package com.sn68.agent.dataagent.agentscope.tool.knowledge;

import lombok.extern.slf4j.Slf4j;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.ToolContextRequestResolver;
import com.sn68.agent.dataagent.agentscope.tool.ToolError;
import com.sn68.agent.dataagent.agentscope.tool.ToolErrorCode;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService.DomainKnowledgeSearchRequest;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService.DomainKnowledgeSearchResult;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService.KnowledgeHit;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 领域业务知识检索工具的执行逻辑：解析入参、调用领域知识搜索并把命中内容裁剪到工具返回预算内，
 * 失败时输出结构化 ToolError 而非抛出异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainBusinessKnowledgeToolSupport {

	/**
	 * ReAct 工具单次返回的正文总量上限，与技能上下文路径 {@code SkillBusinessContextService} 的预算对齐。
	 */
	private static final int MAX_TOOL_RESULT_CHARS = 5000;

	public static final String INPUT_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "query": {
			      "type": "string",
			      "description": "必填。需要检索的业务问题、指标名、术语、SOP 主题或案例主题。"
			    },
				"topK": {
			      "type": "integer",
			      "description": "可选。返回条数，默认 5，最大 8。"
			    },
			    "similarityThreshold": {
			      "type": "number",
			      "description": "可选。相似度阈值，范围 0 到 1，默认 0.2。"
			    }
			  },
			  "required": ["query"]
			}
			""";

	private final ObjectMapper objectMapper;

	private final DomainKnowledgeSearchService domainKnowledgeSearchService;

	public ToolCallback createSearchToolCallback(SkillVersionResources resources, String toolName, String description) {
		ToolDefinition toolDefinition = ToolDefinition.builder()
			.name(toolName)
			.description(description)
			.inputSchema(INPUT_SCHEMA)
			.build();
		return new DomainBusinessKnowledgeSearchToolCallback(toolDefinition, objectMapper, domainKnowledgeSearchService,
				resources);
	}

	private static final class DomainBusinessKnowledgeSearchToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition;

		private final ObjectMapper objectMapper;

		private final DomainKnowledgeSearchService domainKnowledgeSearchService;

		private final SkillVersionResources resources;

		private DomainBusinessKnowledgeSearchToolCallback(ToolDefinition toolDefinition, ObjectMapper objectMapper,
				DomainKnowledgeSearchService domainKnowledgeSearchService, SkillVersionResources resources) {
			this.toolDefinition = toolDefinition;
			this.objectMapper = objectMapper;
			this.domainKnowledgeSearchService = domainKnowledgeSearchService;
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
				JsonNode jsonNode = StringUtils.hasText(toolInput) ? objectMapper.readTree(toolInput)
						: objectMapper.createObjectNode();
				String query = jsonNode.path("query").asText("");
				validateQuery(query);
				Integer topK = jsonNode.has("topK") && jsonNode.get("topK").canConvertToInt()
						? jsonNode.get("topK").asInt() : null;
				Double similarityThreshold = jsonNode.has("similarityThreshold")
						&& jsonNode.get("similarityThreshold").isNumber()
								? jsonNode.get("similarityThreshold").asDouble() : null;

				AgentRequest agentRequest = ToolContextRequestResolver.resolveGraphRequest(toolContext);
				if (resources != null) {
					validateSkillContext(agentRequest);
					DomainKnowledgeSearchRequest request = new DomainKnowledgeSearchRequest(query,
							List.of("businessKnowledge"), topK, similarityThreshold);
					return objectMapper.writeValueAsString(capToBudget(domainKnowledgeSearchService
						.searchSkillBusinessKnowledge(resources.skillId(), resources.businessKnowledgeIds(), request,
								agentRequest)));
				}
				throw new IllegalArgumentException("Published Skill resource snapshot is required");
			}
			catch (Exception ex) {
				throw new IllegalStateException(objectToJson(ToolError.of(ToolErrorCode.EXECUTION_FAILED,
						"业务知识检索工具执行失败：" + ex.getMessage())), ex);
			}
		}

		private void validateQuery(String query) {
			if (!StringUtils.hasText(query)) {
				throw new IllegalArgumentException(objectToJson(
						ToolError.of(ToolErrorCode.INVALID_INPUT, "业务知识检索工具需要 query 参数")));
			}
		}

		private void validateSkillContext(AgentRequest agentRequest) {
			if (agentRequest == null || resources == null || resources.skillId() == null
					|| resources.skillVersionId() == null
					|| !resources.skillId().equals(agentRequest.getRoutedSkillId())
					|| !resources.skillVersionId().equals(agentRequest.getRoutedSkillVersionId())
					|| agentRequest.getRoutedSkillResources() == null
					|| !resources.skillId().equals(agentRequest.getRoutedSkillResources().skillId())
					|| !resources.skillVersionId().equals(agentRequest.getRoutedSkillResources().skillVersionId())) {
				throw new IllegalArgumentException("Published Skill resource snapshot is required");
			}
		}

		/**
		 * 给 ReAct 工具结果加总量预算。
		 *
		 * <p>
		 * 技能上下文那条路径有 {@code SkillBusinessContextService} 的 5000 字预算兜底，而这里是把整个结果对象
		 * 直接序列化交给模型，此前没有任何上限：topK 上限 8 条、单条片段上限 1000 字，一次调用即可产出约 9.5KB，
		 * 再乘以工具调用次数足以顶满上下文。两条路径对齐到同一预算。
		 *
		 * <p>
		 * 裁剪按条丢弃而不截断单条正文——半截的业务知识比没有更容易误导模型；被丢弃的条数写进
		 * {@code warnings}，让模型知道「还有更多、可以缩小提问范围」，而不是以为检索结果就这些。
		 */
		private DomainKnowledgeSearchResult capToBudget(DomainKnowledgeSearchResult result) {
			if (result == null || CollectionUtils.isEmpty(result.hits())) {
				return result;
			}
			List<KnowledgeHit> kept = new ArrayList<>();
			int used = 0;
			for (KnowledgeHit hit : result.hits()) {
				int cost = length(hit.snippet()) + length(hit.summary()) + length(hit.title());
				// 首条无论多长都保留，否则单条超预算时会返回空结果，比超预算更糟。
				if (!kept.isEmpty() && used + cost > MAX_TOOL_RESULT_CHARS) {
					break;
				}
				kept.add(hit);
				used += cost;
			}
			if (kept.size() == result.hits().size()) {
				return result;
			}
			List<String> warnings = new ArrayList<>(result.warnings() == null ? List.of() : result.warnings());
			warnings.add("检索结果超出单次返回上限，已保留相关度最高的 " + kept.size() + " 条，省略 "
					+ (result.hits().size() - kept.size()) + " 条；如需更精确的结果请缩小提问范围。");
			return new DomainKnowledgeSearchResult(kept, warnings, result.resolution());
		}

		private static int length(String value) {
			return value == null ? 0 : value.length();
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
