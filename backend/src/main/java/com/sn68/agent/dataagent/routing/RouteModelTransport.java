/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelTokenAccounting;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsOverride;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ResolvedModelRequestOptions;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The sole transport used by capability probing and runtime disambiguation.
 * It keeps provider diagnostics bounded to response metadata and never stores
 * raw provider responses.
 */
@Service
public class RouteModelTransport {

	private static final long ROUTE_FINAL_JSON_RESERVE = 256L;

	private static final Pattern HTTP_STATUS = Pattern.compile("(?<!\\d)([45]\\d{2})(?!\\d)");

	private final DynamicModelFactory modelFactory;

	private final RouteModelCallMetrics callMetrics;

	public RouteModelTransport(DynamicModelFactory modelFactory) {
		this(modelFactory, RouteModelCallMetrics.noop());
	}

	@Autowired
	public RouteModelTransport(DynamicModelFactory modelFactory, RouteModelCallMetrics callMetrics) {
		this.modelFactory = modelFactory;
		this.callMetrics = callMetrics == null ? RouteModelCallMetrics.noop() : callMetrics;
	}

	public Response call(ModelConfigDTO config, RouteModelOutputProtocol protocol, Duration timeout,
			Duration connectTimeout, String systemPrompt, String userPrompt) {
		return call(config, protocol, timeout, connectTimeout, systemPrompt, userPrompt, RouteModelCallTelemetry.start());
	}

	Response call(ModelConfigDTO config, RouteModelOutputProtocol protocol, Duration timeout,
			Duration connectTimeout, String systemPrompt, String userPrompt, RouteModelCallTelemetry.Call telemetry) {
		RouteModelCallTelemetry.Call call = telemetry == null ? RouteModelCallTelemetry.start() : telemetry;
		call.markTransportStarted(systemPrompt, userPrompt);
		try (RouteModelCallTelemetry.Scope ignored = RouteModelCallTelemetry.attach(call)) {
			ModelRequestOptionsOverride baseOverride = routeOverride(config, protocol);
			ResolvedModelRequestOptions resolved = modelFactory.resolveRequestOptions(config, baseOverride, null);
			long outputTokens = routeOutputTokens(config, resolved);
			ModelRequestOptionsOverride taskOverride = baseOverride.toBuilder()
				.maxOutputTokens(outputTokens)
				.temperature(0D)
				.build();
			try {
				ChatModel model = modelFactory.createRouteModelForProtocol(config, timeout, connectTimeout, protocol,
						taskOverride, null, RouteModelProtocol.JSON_SCHEMA);
				ChatResponse response = model.call(new Prompt(java.util.List.of(new SystemMessage(systemPrompt),
							new UserMessage(userPrompt))));
				Generation generation = response == null ? null : response.getResult();
				String content = extractContent(generation, protocol);
				ChatGenerationMetadata generationMetadata = generation == null ? null : generation.getMetadata();
				ChatResponseMetadata responseMetadata = response == null ? null : response.getMetadata();
				Usage usage = responseMetadata == null ? null : responseMetadata.getUsage();
				call.recordResponseMetadataId(responseMetadata == null ? null : responseMetadata.getId());
				Response result = new Response(content,
						generationMetadata == null ? null : generationMetadata.getFinishReason(),
						usage == null ? null : usage.getTotalTokens());
				finish(call, "SUCCESS", null);
				return result;
			}
			catch (RuntimeException ex) {
				Integer httpStatus = httpStatus(ex);
				String failureCode = failureCode(ex, httpStatus);
				finish(call, "FAILED", "ROUTE_MODEL_TIMEOUT".equals(failureCode) ? failureCode : null);
				throw new RouteModelTransportException(classify(ex, httpStatus), httpStatus, failureCode,
						"Route model transport failed", ex);
			}
		}
	}

	void recordLocalTimeout(RouteModelCallTelemetry.Call telemetry, String reason) {
		finish(telemetry, "TIMED_OUT", reason);
	}

	private void finish(RouteModelCallTelemetry.Call telemetry, String outcome, String timeoutReason) {
		if (telemetry != null && telemetry.finish(outcome, timeoutReason)) {
			callMetrics.record(telemetry.snapshot(outcome, timeoutReason));
		}
	}

	private ModelRequestOptionsOverride routeOverride(ModelConfigDTO config, RouteModelOutputProtocol protocol) {
		ModelRequestOptionsOverride.ModelRequestOptionsOverrideBuilder builder = ModelRequestOptionsOverride.builder();
		if (isDashScope(config) && protocol != RouteModelOutputProtocol.STRICT_SCHEMA) {
			builder.reasoningMode(ModelReasoningMode.DISABLED)
				.reasoningLevel(com.sn68.agent.dataagent.enums.ModelReasoningLevel.NONE);
		}
		return builder.build();
	}

	private boolean isDashScope(ModelConfigDTO config) {
		return config != null && ModelEndpointDialect.DASHSCOPE_NATIVE.name().equalsIgnoreCase(config.getEndpointDialect());
	}

	private String extractContent(Generation generation, RouteModelOutputProtocol protocol) {
		if (generation == null || generation.getOutput() == null) {
			return null;
		}
		if (protocol != RouteModelOutputProtocol.FUNCTION_CALL) {
			return generation.getOutput().getText();
		}
		AssistantMessage assistantMessage = generation.getOutput();
		if (assistantMessage.getToolCalls() == null || assistantMessage.getToolCalls().size() != 1) {
			return null;
		}
		AssistantMessage.ToolCall toolCall = assistantMessage.getToolCalls().get(0);
		if (!"submit_route_plan".equals(toolCall.name()) || toolCall.arguments() == null
				|| toolCall.arguments().isBlank()) {
			return null;
		}
		return toolCall.arguments();
	}

	private long routeOutputTokens(ModelConfigDTO config, ResolvedModelRequestOptions effective) {
		if (config == null || effective == null) {
			return ROUTE_FINAL_JSON_RESERVE;
		}
		return routeOutputTokens(config, effective.reasoningProtocol(), effective.reasoningMode(),
				effective.reasoningBudgetTokens(), effective.tokenAccounting());
	}

	private long routeOutputTokens(ModelConfigDTO config, ModelReasoningProtocol protocol, ModelReasoningMode mode,
			Long reasoningBudget, ModelTokenAccounting tokenAccounting) {
		if (protocol == ModelReasoningProtocol.NONE) {
			if (tokenAccounting != ModelTokenAccounting.OUTPUT_ONLY) {
				throw new IllegalArgumentException(
						"Route model without a reasoning protocol requires OUTPUT_ONLY token accounting");
			}
			return requireAvailableBudget(config, ROUTE_FINAL_JSON_RESERVE, ROUTE_FINAL_JSON_RESERVE);
		}
		if (mode == ModelReasoningMode.DISABLED) {
			return requireAvailableBudget(config, ROUTE_FINAL_JSON_RESERVE, ROUTE_FINAL_JSON_RESERVE);
		}
		if (tokenAccounting == null || tokenAccounting == ModelTokenAccounting.UNKNOWN) {
			throw new IllegalArgumentException("Route reasoning token accounting must be explicitly supported");
		}
		if (tokenAccounting == ModelTokenAccounting.OUTPUT_ONLY) {
			return requireAvailableBudget(config, ROUTE_FINAL_JSON_RESERVE, ROUTE_FINAL_JSON_RESERVE);
		}
		long completionBudget = completionBudget(reasoningBudget);
		return switch (tokenAccounting) {
			case SEPARATE_REASONING_BUDGET ->
				requireAvailableBudget(config, ROUTE_FINAL_JSON_RESERVE, completionBudget);
			case MAX_TOKENS_INCLUDES_REASONING ->
				requireAvailableBudget(config, completionBudget, completionBudget);
			case OUTPUT_ONLY, UNKNOWN -> throw new IllegalStateException("token accounting was handled earlier");
		};
	}

	private long completionBudget(Long reasoningBudget) {
		if (reasoningBudget == null) {
			throw new IllegalArgumentException("Route reasoning requires an explicit reasoningBudgetTokens value");
		}
		if (reasoningBudget <= 0 || reasoningBudget > Long.MAX_VALUE - ROUTE_FINAL_JSON_RESERVE) {
			throw new IllegalArgumentException("reasoningBudgetTokens cannot be reserved for route output");
		}
		return reasoningBudget + ROUTE_FINAL_JSON_RESERVE;
	}

	private long requireAvailableBudget(ModelConfigDTO config, long outputLimit, long contextCompletionBudget) {
		if (config.getMaxTokens() != null && config.getMaxTokens() < outputLimit) {
			throw new IllegalArgumentException("Route model maxTokens must reserve at least " + outputLimit
					+ " output tokens");
		}
		if (config.getContextWindowTokens() != null && config.getContextWindowTokens() < contextCompletionBudget) {
			throw new IllegalArgumentException("Route model contextWindowTokens must reserve at least "
					+ contextCompletionBudget
					+ " completion tokens");
		}
		return outputLimit;
	}

	private Integer httpStatus(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof RestClientResponseException responseException) {
				return responseException.getStatusCode().value();
			}
			if (current instanceof WebClientResponseException responseException) {
				return responseException.getStatusCode().value();
			}
		}
		Matcher matcher = HTTP_STATUS.matcher(messages(throwable));
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}

	private RouteModelTransportException.FailureKind classify(Throwable throwable, Integer httpStatus) {
		if (httpStatus != null) {
			if (httpStatus == 408 || httpStatus == 429 || (httpStatus >= 500 && httpStatus <= 599)) {
				return RouteModelTransportException.FailureKind.UNAVAILABLE;
			}
			if ((httpStatus == 400 || httpStatus == 422) && isProtocolRejected(throwable)) {
				return RouteModelTransportException.FailureKind.PROTOCOL_REJECTED;
			}
			if (httpStatus >= 400 && httpStatus <= 499) {
				return RouteModelTransportException.FailureKind.FAILED;
			}
		}
		if (isProtocolRejected(throwable)) {
			return RouteModelTransportException.FailureKind.PROTOCOL_REJECTED;
		}
		if (isUnavailable(throwable)) {
			return RouteModelTransportException.FailureKind.UNAVAILABLE;
		}
		return RouteModelTransportException.FailureKind.FAILED;
	}

	private String failureCode(Throwable throwable, Integer httpStatus) {
		if (httpStatus != null) {
			if (httpStatus == 429) {
				return "ROUTE_MODEL_RATE_LIMITED";
			}
			if (httpStatus >= 500 && httpStatus <= 599) {
				return "ROUTE_MODEL_SERVER_ERROR";
			}
			if (httpStatus == 408) {
				return "ROUTE_MODEL_TIMEOUT";
			}
			if (httpStatus >= 400 && httpStatus <= 499) {
				return "ROUTE_MODEL_REQUEST_FAILED";
			}
		}
		if (isTimeout(throwable)) {
			return "ROUTE_MODEL_TIMEOUT";
		}
		if (isConnectionFailure(throwable)) {
			return "ROUTE_MODEL_CONNECTION_FAILED";
		}
		if (isUnavailable(throwable)) {
			return "ROUTE_MODEL_NETWORK_ERROR";
		}
		return "ROUTE_MODEL_PROBE_FAILED";
	}

	private boolean isProtocolRejected(Throwable throwable) {
		String message = messages(throwable);
		boolean protocolParameter = message.contains("response_format") || message.contains("json_schema")
				|| message.contains("json schema") || message.contains("json_object") || message.contains("strict")
				|| message.contains("tool_choice") || message.contains("parallel_tool_calls")
				|| message.contains("function calling") || message.contains("structuredoutputmode")
				|| message.contains("structured output");
		boolean clientRejection = message.contains("400") || message.contains("422") || message.contains("bad request")
				|| message.contains("not support") || message.contains("unsupported");
		return protocolParameter && clientRejection;
	}

	private boolean isUnavailable(Throwable throwable) {
		if (isTimeout(throwable) || isConnectionFailure(throwable)) {
			return true;
		}
		String message = messages(throwable);
		return message.contains("unknown host") || message.contains("429") || message.contains("500")
				|| message.contains("502") || message.contains("503") || message.contains("504");
	}

	private boolean isTimeout(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SocketTimeoutException || current instanceof TimeoutException) {
				return true;
			}
		}
		String message = messages(throwable);
		return message.contains("timed out") || message.contains("timeout");
	}

	private boolean isConnectionFailure(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof ConnectException) {
				return true;
			}
		}
		String message = messages(throwable);
		return message.contains("connection refused") || message.contains("connection reset")
				|| message.contains("connection closed");
	}

	private String messages(Throwable throwable) {
		StringBuilder builder = new StringBuilder();
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				builder.append(current.getMessage()).append(' ');
			}
		}
		return builder.toString().toLowerCase(Locale.ROOT);
	}

	public record Response(String content, String finishReason, Integer totalTokens) {
	}

}
