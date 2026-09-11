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

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.tool.SkillResourceToolProvider;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 领域业务知识检索工具的注册入口：声明工具名、面向模型的使用说明与输入 Schema，实际检索由 Support 承担。
 */
@Component
@RequiredArgsConstructor
public class DomainBusinessKnowledgeToolProvider implements SkillResourceToolProvider {

	private static final String TOOL_NAME = AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH;

	private static final String DESCRIPTION = """
			按需检索当前 Agent 已召回的业务术语、FAQ、问答条目和嵌入文档。
			当回答依赖公司介绍、产品、解决方案、服务能力、行业场景、联系方式、合作方式、内部业务定义、指标口径、SOP、历史案例或领域术语澄清时，使用本工具。
			只有当答案确实依赖领域知识，而不是通用推理或数据库物理结构本身时，才调用本工具。
			不要把本工具用于数据库表名、列名、字段类型、枚举值、表关系、字段注释或其他表结构解释问题；这些问题应先交给数据源探索工具，如仍需补充语义，再考虑 `semantic_model_search`。
			""";

	private final DomainBusinessKnowledgeToolSupport toolSupport;

	@Override
	public Map<String, ToolCallback> getSkillToolCallbacks(SkillVersionResources resources) {
		if (resources == null || resources.businessKnowledgeIds().isEmpty()) {
			return Map.of();
		}
		return Map.of(TOOL_NAME, toolSupport.createSearchToolCallback(resources, TOOL_NAME, DESCRIPTION));
	}

}
