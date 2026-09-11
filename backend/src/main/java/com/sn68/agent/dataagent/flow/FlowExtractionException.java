/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * FLOW 字段抽取失败，携带稳定错误编码供流程与 Turn 状态使用。
 */
public class FlowExtractionException extends RuntimeException {

	public static final String TIMEOUT = "FLOW_EXTRACT_TIMEOUT";

	public static final String PROVIDER_ERROR = "FLOW_EXTRACT_PROVIDER_ERROR";

	public static final String INVALID_RESPONSE = "FLOW_EXTRACT_INVALID_RESPONSE";

	public static final String CONFIG_ERROR = "FLOW_EXTRACT_CONFIG_ERROR";

	public static final String TRUNCATED = "FLOW_EXTRACT_TRUNCATED";

	public static final String INPUT_TOO_LARGE = "FLOW_EXTRACT_INPUT_TOO_LARGE";

	public static final String AUTHENTICATION_ERROR = "FLOW_EXTRACT_AUTHENTICATION_ERROR";

	public static final String PROTOCOL_INCOMPATIBLE = "FLOW_EXTRACT_PROTOCOL_INCOMPATIBLE";

	public static final String RATE_LIMITED = "FLOW_EXTRACT_RATE_LIMITED";

	public static final String UPSTREAM_ERROR = "FLOW_EXTRACT_UPSTREAM_ERROR";

	private static final Pattern HTTP_STATUS = Pattern.compile("(?<!\\d)(400|401|403|408|413|422|429|5\\d{2})(?!\\d)");

	private final String errorCode;

	public FlowExtractionException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}

	public static FlowExtractionException modelCall(Throwable error) {
		if (isTimeout(error)) {
			return new FlowExtractionException(TIMEOUT, "FLOW extraction timed out", error);
		}
		if (isInputTooLarge(error)) {
			return new FlowExtractionException(INPUT_TOO_LARGE, "FLOW extraction input is too large", error);
		}
		if (isAuthenticationError(error)) {
			return new FlowExtractionException(AUTHENTICATION_ERROR, "FLOW extraction authentication failed", error);
		}
		if (isSchemaProtocolRejected(error)) {
			return new FlowExtractionException(PROTOCOL_INCOMPATIBLE,
					"FLOW extraction response protocol is unsupported", error);
		}
		Integer status = httpStatus(error);
		if (status != null && status == 429) {
			return new FlowExtractionException(RATE_LIMITED, "FLOW extraction provider rate limited", error);
		}
		if (status != null && status >= 500 && status <= 599) {
			return new FlowExtractionException(UPSTREAM_ERROR, "FLOW extraction provider is unavailable", error);
		}
		return new FlowExtractionException(PROVIDER_ERROR, "FLOW extraction provider call failed", error);
	}

	public static FlowExtractionException configuration(Throwable error) {
		return new FlowExtractionException(CONFIG_ERROR, "FLOW extraction model configuration is invalid", error);
	}

	public static boolean isSchemaProtocolRejected(Throwable error) {
		Integer status = httpStatus(error);
		if (status == null || (status != 400 && status != 422)) {
			return false;
		}
		String message = messages(error);
		return message.contains("response_format") || message.contains("response format")
				|| message.contains("json_schema") || message.contains("json schema")
				|| message.contains("json_object") || message.contains("json object") || message.contains("strict")
				|| message.contains("tool_choice") || message.contains("parallel_tool_calls")
				|| message.contains("function calling") || message.contains("tool calls") || message.contains("tools");
	}

	private static boolean isTimeout(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
				return true;
			}
			String name = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
			String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
			if (name.contains("timeout") || message.contains("timed out") || message.contains("deadline has expired")) {
				return true;
			}
		}
		Integer status = httpStatus(error);
		return status != null && (status == 408 || status == 504);
	}

	private static boolean isInputTooLarge(Throwable error) {
		Integer status = httpStatus(error);
		if (status != null && status == 413) {
			return true;
		}
		String message = messages(error);
		return message.contains("context length") || message.contains("maximum context")
				|| message.contains("input too long") || message.contains("too many tokens")
				|| message.contains("payload too large") || message.contains("request too large");
	}

	private static boolean isAuthenticationError(Throwable error) {
		Integer status = httpStatus(error);
		if (status != null && (status == 401 || status == 403)) {
			return true;
		}
		String message = messages(error);
		return message.contains("invalid api key") || message.contains("invalid_api_key")
				|| message.contains("unauthorized") || message.contains("authentication failed");
	}

	private static Integer httpStatus(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof RestClientResponseException responseException) {
				return responseException.getStatusCode().value();
			}
			if (current instanceof WebClientResponseException responseException) {
				return responseException.getStatusCode().value();
			}
		}
		Matcher matcher = HTTP_STATUS.matcher(messages(error));
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}

	private static String messages(Throwable error) {
		StringBuilder builder = new StringBuilder();
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				builder.append(current.getMessage()).append(' ');
			}
			if (current instanceof RestClientResponseException responseException) {
				builder.append(responseException.getResponseBodyAsString()).append(' ');
			}
			if (current instanceof WebClientResponseException responseException) {
				builder.append(responseException.getResponseBodyAsString()).append(' ');
			}
		}
		return builder.toString().toLowerCase(Locale.ROOT);
	}

}
