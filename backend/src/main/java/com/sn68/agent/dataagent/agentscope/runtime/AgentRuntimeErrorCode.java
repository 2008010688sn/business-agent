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

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * 智能体运行时错误码。
 */
public enum AgentRuntimeErrorCode implements DictEnum<String> {

	TIMEOUT("agent_runtime_timeout", "模型服务响应超时，系统已停止生成。请稍后重试；若连续失败，可缩小问题范围后再问。", "timeout", true),

	AUTHENTICATION_FAILED("agent_runtime_authentication_failed", "模型服务鉴权失败，系统已停止生成。请检查 API Key 或模型配置后重试。", "auth", false),

	ACCESS_DENIED("agent_runtime_access_denied",
			"模型服务拒绝访问，系统已停止生成。可能是账号余额或额度不足、模型权限受限，或 API Key 无权访问当前模型。请检查模型服务账号和配置。",
			"auth", false),

	QUOTA_EXHAUSTED("agent_runtime_quota_exhausted", "模型服务余额或额度不足，系统已停止生成。请检查模型服务账号余额、免费额度或资源包后重试。", "quota",
			false),

	RATE_LIMITED("agent_runtime_rate_limited", "模型服务请求过于频繁或额度受限，系统已停止生成。请稍后重试，或检查模型服务限流和额度配置。", "rate_limit",
			true),

	UPSTREAM_NOT_FOUND("agent_runtime_upstream_not_found",
			"模型服务接口或模型不存在，系统已停止生成。请检查 Base URL、接口路径和模型名称。", "upstream", false),

	UPSTREAM_UNAVAILABLE("agent_runtime_upstream_unavailable", "模型服务暂时不可用，系统已停止生成。请稍后重试。", "upstream", true),

	LEGAL_RESTRICTED("agent_runtime_legal_restricted",
			"模型服务因地区、合规或内容策略限制拒绝本次请求，系统已停止生成。请检查模型服务 Base URL、账号区域、网络出口或服务商合规配置。",
			"legal", false),

	TOOL_FAILED("agent_runtime_tool_failed", "工具调用失败，系统已停止生成。请稍后重试，或调整问题后重新提问。", "tool", true),

	MODEL_CONFIG_INVALID("agent_runtime_model_config_invalid", "当前模型配置不可用，系统已停止生成。请检查模型配置后重试。", "model_config",
			false),

	MODEL_REQUEST_INVALID("agent_runtime_model_request_invalid",
			"模型服务无法处理当前请求，系统已停止生成。请检查模型及工具配置后再试。", "model_request", false),

	MODEL_PROTOCOL_ERROR("MODEL_PROTOCOL_ERROR", "模型返回格式异常，系统未执行推理文本中的伪工具调用，请稍后重试。", "model_protocol", true),

	MODEL_EMPTY_COMPLETION("MODEL_EMPTY_COMPLETION", "模型没有生成可用答案，请稍后重试或缩小问题。", "model_protocol", true),

	BUDGET_EXCEEDED("agent_runtime_budget_exceeded",
			"本次分析步骤较多，已达到智能体执行上限。请缩小问题范围后重试，或联系管理员调整执行预算。", "budget", false),

	ROUTE_UNAVAILABLE("ROUTE_UNAVAILABLE",
			"智能路由暂时不可用，系统未执行任何业务能力。请稍后重试或联系管理员检查路由配置。", "route", true),

	SESSION_BUSY("agent_runtime_session_busy", "当前会话已有运行中的请求，请等待完成后再继续提问。", "session", true),

	CLARIFICATION_EXPIRED("CLARIFICATION_EXPIRED", "澄清已过期或已失效，请重新提问。", "clarification", true),

	CLARIFICATION_CONSUMED("CLARIFICATION_CONSUMED", "澄清已被使用，请重新提问。", "clarification", true),

	UNKNOWN("agent_runtime_unknown", "本次分析失败，系统已停止生成。请稍后重试，或调整问题后重新提问。", "unknown", true);

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	private final String category;

	private final boolean retryable;

	AgentRuntimeErrorCode(String value, String label, String category, boolean retryable) {
		this.value = value;
		this.label = label;
		this.category = category;
		this.retryable = retryable;
	}

	@Override
	public String getValue() {
		return value;
	}

	@Override
	public String getLabel() {
		return label;
	}

	public String getCategory() {
		return category;
	}

	public boolean isRetryable() {
		return retryable;
	}

}
