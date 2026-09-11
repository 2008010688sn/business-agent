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
package com.sn68.agent.dataagent.agentscope.v2;

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig;
import com.sn68.agent.dataagent.service.analysis.AnalysisTurnDecision;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

/**
 * v2 Toolkit 装配过滤：分析通道只读，FILE_ONLY 不挂 JDBC / 执行 MCP / 写工具。
 *
 * <p>装配：{@code V2ToolkitFilter.filter(callbacks, intent)} 后再
 * {@code toolkitFactory.buildToolkit(...)}；intent 同时写入 RuntimeContext
 * {@code V2ActingPermissionMiddleware.ANALYSIS_INTENT_KEY}。
 */
public final class V2ToolkitFilter {

	public static final String INTENT_FILE_ONLY = "FILE_ONLY";

	public static final String INTENT_FILE_JOIN = "FILE_JOIN";

	public static final String INTENT_ANALYSIS = "ANALYSIS";

	private static final Pattern WRITE_OR_EXECUTE = Pattern.compile(
			"(?i)(^|_|-)(execute|create|update|delete|insert|write|submit|drop|alter|truncate)(_|-|$)");

	private V2ToolkitFilter() {
	}

	/**
	 * 无分析源图且非报告意图时返回 {@code null}（闲聊 / 普通 NL2SQL 不进分析通道）。
	 * 纯文件源图恒为 FILE_ONLY；其余按回合分类映射到 FILE_ONLY / FILE_JOIN / ANALYSIS。
	 */
	public static String resolve(AnalysisConfig config, boolean reportMode, AnalysisTurnDecision decision) {
		if ((config == null || !config.present()) && !reportMode) {
			return null;
		}
		if (config != null && config.fileOnly()) {
			return INTENT_FILE_ONLY;
		}
		if (decision != null && decision.intent() == AnalysisTurnDecision.Intent.FILE_ONLY) {
			return INTENT_FILE_ONLY;
		}
		if (decision != null && decision.intent() == AnalysisTurnDecision.Intent.FILE_JOIN) {
			return INTENT_FILE_JOIN;
		}
		return INTENT_ANALYSIS;
	}

	public static Map<String, ToolCallback> filter(Map<String, ToolCallback> callbacks, String analysisIntent) {
		if (callbacks == null || callbacks.isEmpty()) {
			return Map.of();
		}
		if (!isAnalysisChannel(analysisIntent)) {
			return callbacks;
		}
		Map<String, ToolCallback> filtered = new LinkedHashMap<>();
		callbacks.forEach((name, callback) -> {
			if (allowedInToolkit(name, analysisIntent)) {
				filtered.put(name, callback);
			}
		});
		return filtered;
	}

	public static boolean allowedInToolkit(String toolName, String analysisIntent) {
		if (!StringUtils.hasText(toolName)) {
			return false;
		}
		if (!isAnalysisChannel(analysisIntent)) {
			return true;
		}
		if (isExecuteMcp(toolName) || isWriteToolName(toolName)) {
			return false;
		}
		if (isFileOnly(analysisIntent) && isJdbcTool(toolName)) {
			return false;
		}
		return true;
	}

	public static boolean isAnalysisChannel(String analysisIntent) {
		if (!StringUtils.hasText(analysisIntent)) {
			return false;
		}
		String intent = analysisIntent.trim().toUpperCase(Locale.ROOT);
		return INTENT_FILE_ONLY.equals(intent) || INTENT_FILE_JOIN.equals(intent) || INTENT_ANALYSIS.equals(intent);
	}

	public static boolean isFileOnly(String analysisIntent) {
		return StringUtils.hasText(analysisIntent)
				&& INTENT_FILE_ONLY.equals(analysisIntent.trim().toUpperCase(Locale.ROOT));
	}

	public static boolean isReadOnly(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return false;
		}
		if (isExecuteMcp(toolName) || isWriteToolName(toolName)) {
			return false;
		}
		return AgentModelToolName.isDataQueryTool(toolName) || AgentModelToolName.isKnowledgeTool(toolName)
				|| AgentModelToolName.isWebEvidenceTool(toolName);
	}

	public static boolean isExecuteMcp(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return false;
		}
		if (AgentModelToolName.isSkillActionTool(toolName)) {
			return true;
		}
		return toolName.contains("__") && WRITE_OR_EXECUTE.matcher(toolName).find();
	}

	public static boolean isWriteToolName(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return false;
		}
		if (AgentModelToolName.isSkillActionTool(toolName)) {
			return true;
		}
		if (AgentModelToolName.isDataQueryTool(toolName) || AgentModelToolName.isKnowledgeTool(toolName)
				|| AgentModelToolName.isWebEvidenceTool(toolName)) {
			return false;
		}
		return WRITE_OR_EXECUTE.matcher(toolName).find();
	}

	public static boolean isJdbcTool(String toolName) {
		return AgentModelToolName.isDatasourceTool(toolName) || AgentModelToolName.isSemanticModelTool(toolName)
				|| AgentModelToolName.isSqlGuardTool(toolName);
	}

}
