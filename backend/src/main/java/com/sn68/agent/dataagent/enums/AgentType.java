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
package com.sn68.agent.dataagent.enums;

import java.util.Locale;

import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.Getter;
import org.springframework.util.StringUtils;

/**
 * 智能体类型（code 为对外小写编码并兼容历史别名，历史契约保持不动）。
 */
@Getter
public enum AgentType implements DictEnum<String> {

	DATA_ANALYSIS("commonagent", "数据分析智能体", "commonagent"),

	KNOWLEDGE_BASE("knowledge_base", "知识库智能体", "knowledgebaseagent"),

	CUSTOMER_SERVICE("customer_service", "客服智能体", "customerserviceagent"),

	ORCHESTRATOR("orchestrator", "编排智能体", "orchestratoragent");

	public static final String LEGACY_COMMON_AGENT = "commonagent";

	private static final String LEGACY_DATA_ANALYSIS_AGENT = "data_analysis";

	private final String code;

	private final String description;

	private final String promptName;

	AgentType(String code, String description, String promptName) {
		this.code = code;
		this.description = description;
		this.promptName = promptName;
	}

	@Override
	public String getValue() {
		return code;
	}

	@Override
	public String getLabel() {
		return description;
	}

	/**
	 * 根据编码获取枚举（先归一化并兼容历史别名），未匹配抛出异常。
	 */
	public static AgentType fromCode(String code) {
		String normalizedCode = normalizeCode(code);
		for (AgentType agentType : values()) {
			if (agentType.getCode().equals(normalizedCode)) {
				return agentType;
			}
		}
		throw new IllegalArgumentException("未知智能体类型编码：" + code);
	}

	public static String normalizeCode(String code) {
		if (!StringUtils.hasText(code)) {
			return DATA_ANALYSIS.getCode();
		}
		String normalizedCode = code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		if (LEGACY_COMMON_AGENT.equals(normalizedCode) || LEGACY_DATA_ANALYSIS_AGENT.equals(normalizedCode)) {
			return DATA_ANALYSIS.getCode();
		}
		return normalizedCode;
	}

	public static String promptName(String code) {
		String normalizedCode = normalizeCode(code);
		for (AgentType agentType : values()) {
			if (agentType.getCode().equals(normalizedCode)) {
				return agentType.getPromptName();
			}
		}
		return normalizedCode;
	}

	public static boolean isOrchestrator(String code) {
		return ORCHESTRATOR.getCode().equals(normalizeCode(code));
	}

	public static boolean isDataAnalysis(String code) {
		return DATA_ANALYSIS.getCode().equals(normalizeCode(code));
	}

}
