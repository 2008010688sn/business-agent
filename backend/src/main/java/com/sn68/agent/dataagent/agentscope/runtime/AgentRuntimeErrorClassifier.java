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

import com.sn68.agent.dataagent.routing.RouteUnavailableException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 智能体运行时异常分类器。
 */
public final class AgentRuntimeErrorClassifier {

	private static final List<String> QUOTA_KEYWORDS = List.of("insufficient_quota", "insufficient quota",
			"quotaexceeded", "quota exceeded", "over quota", "billing", "balance", "credit", "余额", "额度", "欠费",
			"资源包", AgentRuntimeErrorCode.QUOTA_EXHAUSTED.getValue());

	private static final List<String> RATE_LIMIT_KEYWORDS = List.of("rate limit", "ratelimit", "too many requests",
			"qps", "限流", "请求过多", "频率过高", AgentRuntimeErrorCode.RATE_LIMITED.getValue());

	private static final List<String> AUTH_KEYWORDS = List.of("unauthorized", "invalid api key", "invalid_api_key",
			"api key invalid", "authentication", "鉴权", "认证失败", "无效的 api key", "登录过期");

	private static final List<String> MODEL_CONFIG_KEYWORDS = List.of("apikey must not be empty",
			"baseurl must not be empty", "modelname must not be empty", "当前未配置可用的 chat 模型", "模型配置不可用");

	private static final List<String> TOOL_KEYWORDS = List.of("工具执行失败", "工具调用失败", "tool execution",
			"tool call", "invalid_input", "unsupported_action", "datasource_unavailable", "table_not_visible",
			"column_not_visible", "execution_failed");

	private static final List<String> UPSTREAM_UNAVAILABLE_KEYWORDS = List.of("service unavailable", "bad gateway",
			"connection refused", "connection reset", "connect timed out", "服务暂时不可用");

	private static final List<String> BUDGET_KEYWORDS = List.of("maximum iterations", "error generating summary",
			"agent runtime budget exceeded", AgentRuntimeErrorCode.BUDGET_EXCEEDED.getValue());

	private AgentRuntimeErrorClassifier() {
	}

	public static AgentRuntimeError classify(Throwable error) {
		if (findCause(error, AgentRuntimeBudgetExceededException.class) != null
				|| isBudgetExhaustedText(flattenErrorText(error))) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.BUDGET_EXCEEDED);
		}
		if (error instanceof RouteUnavailableException routeUnavailable) {
			return AgentRuntimeError.diagnosed(AgentRuntimeErrorCode.ROUTE_UNAVAILABLE, routeUnavailable.reasonCode());
		}
		if (findCause(error, AgentRuntimeModelRequestException.class) != null) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.MODEL_REQUEST_INVALID);
		}
		if (error instanceof AgentRuntimeProtocolException protocol) {
			if (AgentRuntimeErrorCode.MODEL_EMPTY_COMPLETION.getValue().equals(protocol.errorCode())) {
				return AgentRuntimeError.of(AgentRuntimeErrorCode.MODEL_EMPTY_COMPLETION);
			}
			return AgentRuntimeError.of(AgentRuntimeErrorCode.MODEL_PROTOCOL_ERROR);
		}
		if (error instanceof AgentRuntimeToolFailureException && error.getMessage() != null
				&& !error.getMessage().isBlank()) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.TOOL_FAILED, error.getMessage());
		}
		if (isTimeoutError(error)) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.TIMEOUT);
		}
		Integer httpStatus = extractHttpStatus(error);
		if (isUpstreamModelRequestRejected(error, httpStatus)) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.MODEL_REQUEST_INVALID, httpStatus);
		}
		String text = flattenErrorText(error);
		if (httpStatus != null) {
			return classifyHttpStatus(httpStatus, text);
		}
		if (contains(text, MODEL_CONFIG_KEYWORDS)) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.MODEL_CONFIG_INVALID);
		}
		if (contains(text, QUOTA_KEYWORDS)) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.QUOTA_EXHAUSTED);
		}
		if (contains(text, RATE_LIMIT_KEYWORDS)) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.RATE_LIMITED);
		}
		if (contains(text, AUTH_KEYWORDS)) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.AUTHENTICATION_FAILED);
		}
		if (contains(text, TOOL_KEYWORDS)) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.TOOL_FAILED);
		}
		if (text.contains("当前会话已有运行中的请求")) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.SESSION_BUSY);
		}
		if (text.contains("clarification has expired or is not valid for this session")) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.CLARIFICATION_EXPIRED);
		}
		if (text.contains("clarification has already been consumed")) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.CLARIFICATION_CONSUMED);
		}
		if (contains(text, UPSTREAM_UNAVAILABLE_KEYWORDS)) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.UPSTREAM_UNAVAILABLE);
		}
		return AgentRuntimeError.of(AgentRuntimeErrorCode.UNKNOWN);
	}

	private static AgentRuntimeError classifyHttpStatus(int httpStatus, String text) {
		if (httpStatus == 401) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.AUTHENTICATION_FAILED, httpStatus);
		}
		if (httpStatus == 402 || contains(text, QUOTA_KEYWORDS)) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.QUOTA_EXHAUSTED, httpStatus);
		}
		if (httpStatus == 403) {
			if (contains(text, QUOTA_KEYWORDS)) {
				return AgentRuntimeError.of(AgentRuntimeErrorCode.QUOTA_EXHAUSTED, httpStatus);
			}
			return AgentRuntimeError.of(AgentRuntimeErrorCode.ACCESS_DENIED, httpStatus);
		}
		if (httpStatus == 404) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.UPSTREAM_NOT_FOUND, httpStatus);
		}
		if (httpStatus == 408 || httpStatus == 504) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.TIMEOUT, httpStatus);
		}
		if (httpStatus == 429) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.RATE_LIMITED, httpStatus);
		}
		if (httpStatus == 451) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.LEGAL_RESTRICTED, httpStatus);
		}
		if (httpStatus >= 500 && httpStatus <= 599) {
			return AgentRuntimeError.of(AgentRuntimeErrorCode.UPSTREAM_UNAVAILABLE, httpStatus);
		}
		return AgentRuntimeError.of(AgentRuntimeErrorCode.UNKNOWN, httpStatus);
	}

	private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
		Throwable current = error;
		while (current != null) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
			current = current.getCause();
		}
		return null;
	}

	private static Integer extractHttpStatus(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof WebClientResponseException webClientResponseException) {
				return webClientResponseException.getStatusCode().value();
			}
			if (current instanceof RestClientResponseException restClientResponseException) {
				return restClientResponseException.getStatusCode().value();
			}
			if (current instanceof ResponseStatusException responseStatusException) {
				return responseStatusException.getStatusCode().value();
			}
			current = current.getCause();
		}
		return null;
	}

	private static boolean isUpstreamModelRequestRejected(Throwable error, Integer httpStatus) {
		if (httpStatus == null || (httpStatus != 400 && httpStatus != 422)) {
			return false;
		}
		return findCause(error, WebClientResponseException.class) != null
				|| findCause(error, RestClientResponseException.class) != null;
	}

	private static boolean isTimeoutError(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof TimeoutException || current instanceof java.net.SocketTimeoutException) {
				return true;
			}
			String className = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
			if (className.contains("timeout")) {
				return true;
			}
			String message = current.getMessage();
			if (message != null) {
				String normalized = message.toLowerCase(Locale.ROOT);
				if (normalized.contains("timeout") || normalized.contains("timed out") || message.contains("超时")
						|| message.contains("没有剩余时间等待协作者")) {
					return true;
				}
			}
			current = current.getCause();
		}
		return false;
	}

	private static String flattenErrorText(Throwable error) {
		StringBuilder builder = new StringBuilder();
		Throwable current = error;
		while (current != null) {
			append(builder, current.getClass().getSimpleName());
			append(builder, current.getMessage());
			if (current instanceof WebClientResponseException webClientResponseException) {
				append(builder, webClientResponseException.getResponseBodyAsString());
			}
			if (current instanceof RestClientResponseException restClientResponseException) {
				append(builder, restClientResponseException.getResponseBodyAsString());
			}
			current = current.getCause();
		}
		return builder.toString().toLowerCase(Locale.ROOT);
	}

	private static void append(StringBuilder builder, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		if (builder.length() > 0) {
			builder.append('\n');
		}
		builder.append(value);
	}

	public static boolean isBudgetExhaustedText(String text) {
		return contains(text == null ? null : text.toLowerCase(Locale.ROOT), BUDGET_KEYWORDS);
	}

	private static boolean contains(String value, List<String> keywords) {
		if (value == null || value.isBlank()) {
			return false;
		}
		for (String keyword : keywords) {
			if (value.contains(keyword.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

}
