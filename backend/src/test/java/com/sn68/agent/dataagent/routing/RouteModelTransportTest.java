/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelCapabilityProfile;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelPreservedReasoningPolicy;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelTemperaturePolicy;
import com.sn68.agent.dataagent.enums.ModelTokenAccounting;
import com.sn68.agent.dataagent.enums.ModelTokenLimitMode;
import com.sn68.agent.dataagent.routing.RouteModelTransportException.FailureKind;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsOverride;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsResolver;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ResolvedModelRequestOptions;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class RouteModelTransportTest {

	@Test
	void classifiesProtocolRejectionAndRetainsOnlyStatusMetadata() {
		RouteModelTransportException error = callWithFailure(
				new IllegalStateException("HTTP 400: response_format json_schema is unsupported"));

		assertEquals(FailureKind.PROTOCOL_REJECTED, error.failureKind());
		assertEquals(Integer.valueOf(400), error.httpStatus());
		assertEquals("Route model transport failed", error.getMessage());
	}

	@Test
	void functionCallingResponseUsesToolArgumentsInsteadOfAssistantText() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDTO config = ModelConfigDTO.builder().provider("custom").apiKey("key")
			.baseUrl("https://example.com").modelName("route-model").modelType("CHAT")
			.capabilityProfile(ModelCapabilityProfile.NO_REASONING.name()).build();
		when(modelFactory.resolveRequestOptions(eq(config), any(ModelRequestOptionsOverride.class), isNull()))
			.thenReturn(new ModelRequestOptionsResolver().resolve(config));
		ChatModel model = mock(ChatModel.class);
		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "submit_route_plan",
				"{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.0}");
		AssistantMessage message = AssistantMessage.builder().content("this text must be ignored")
			.toolCalls(java.util.List.of(toolCall)).build();
		when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(java.util.List.of(new Generation(message))));
		when(modelFactory.createRouteModelForProtocol(eq(config), any(Duration.class), any(Duration.class),
				eq(RouteModelOutputProtocol.FUNCTION_CALL), any(ModelRequestOptionsOverride.class), isNull(),
				eq(RouteModelProtocol.JSON_SCHEMA))).thenReturn(model);

		RouteModelTransport.Response response = new RouteModelTransport(modelFactory).call(config,
				RouteModelOutputProtocol.FUNCTION_CALL, Duration.ofSeconds(1), Duration.ofSeconds(1), "system", "user");

		assertEquals("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.0}", response.content());
	}

	@Test
	void classifiesRateLimitAsUnavailable() {
		RouteModelTransportException error = callWithFailure(new IllegalStateException("HTTP 429: rate limited"));

		assertEquals(FailureKind.UNAVAILABLE, error.failureKind());
		assertEquals(Integer.valueOf(429), error.httpStatus());
		assertEquals("ROUTE_MODEL_RATE_LIMITED", error.failureCode());
	}

	@Test
	void classifiesServerFailureAsUnavailable() {
		RouteModelTransportException error = callWithFailure(new IllegalStateException("HTTP 503: unavailable"));

		assertEquals(FailureKind.UNAVAILABLE, error.failureKind());
		assertEquals(Integer.valueOf(503), error.httpStatus());
		assertEquals("ROUTE_MODEL_SERVER_ERROR", error.failureCode());
	}

	@Test
	void serverFailureTakesPriorityOverProtocolKeywords() {
		RouteModelTransportException error = callWithFailure(
				new IllegalStateException("HTTP 500: response_format json_schema processing failed"));

		assertEquals(FailureKind.UNAVAILABLE, error.failureKind());
		assertEquals(Integer.valueOf(500), error.httpStatus());
	}

	@Test
	void authenticationFailureTakesPriorityOverProtocolKeywords() {
		RouteModelTransportException error = callWithFailure(
				new IllegalStateException("HTTP 401: response_format json_schema is unsupported"));

		assertEquals(FailureKind.FAILED, error.failureKind());
		assertEquals(Integer.valueOf(401), error.httpStatus());
	}

	@Test
	void preservesTimeoutConnectionAndNetworkFailureCodes() {
		RouteModelTransportException timeout = callWithFailure(
				new IllegalStateException("request failed", new SocketTimeoutException("read timed out")));
		RouteModelTransportException connection = callWithFailure(
				new IllegalStateException("request failed", new ConnectException("connection refused")));
		RouteModelTransportException network = callWithFailure(new IllegalStateException("unknown host"));

		assertEquals("ROUTE_MODEL_TIMEOUT", timeout.failureCode());
		assertEquals("ROUTE_MODEL_CONNECTION_FAILED", connection.failureCode());
		assertEquals("ROUTE_MODEL_NETWORK_ERROR", network.failureCode());
	}

	@Test
	void routeOutputBudgetReservesFinalJsonTokensAfterReasoningBudget() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		AtomicReference<ModelRequestOptionsOverride> captured = new AtomicReference<>();
		ModelConfigDTO config = ModelConfigDTO.builder().provider("qwen").apiKey("key")
			.baseUrl("https://example.com").modelName("qwen-plus").modelType("CHAT")
			.endpointDialect("DASHSCOPE_NATIVE").reasoningMode("ENABLED")
			.reasoningBudgetTokens(512L).maxTokens(2000L).build();
		when(modelFactory.resolveRequestOptions(eq(config), any(ModelRequestOptionsOverride.class), isNull()))
			.thenReturn(new ModelRequestOptionsResolver().resolve(config));
		when(modelFactory.createRouteModelForProtocol(eq(config), any(Duration.class), any(Duration.class),
				eq(RouteModelOutputProtocol.JSON_OBJECT), any(ModelRequestOptionsOverride.class), isNull(),
				eq(RouteModelProtocol.JSON_SCHEMA))).thenAnswer(invocation -> {
			captured.set(invocation.getArgument(4));
			throw new IllegalStateException("capture");
		});
		RouteModelTransport transport = new RouteModelTransport(modelFactory);

		assertThrows(RouteModelTransportException.class,
				() -> transport.call(config, RouteModelOutputProtocol.JSON_OBJECT, Duration.ofSeconds(1),
						Duration.ofSeconds(1), "system", "user"));

		assertEquals(768L, captured.get().maxOutputTokens());
	}

	@Test
	void routeRejectsReasoningBudgetThatCannotFitConfiguredOutputLimit() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDTO config = ModelConfigDTO.builder().provider("qwen").apiKey("key")
			.baseUrl("https://example.com").modelName("qwen-plus").modelType("CHAT")
			.endpointDialect("DASHSCOPE_NATIVE").reasoningMode("ENABLED")
			.reasoningBudgetTokens(512L).maxTokens(600L).build();
		when(modelFactory.resolveRequestOptions(eq(config), any(ModelRequestOptionsOverride.class), isNull()))
			.thenReturn(new ModelRequestOptionsResolver().resolve(config));
		RouteModelTransport transport = new RouteModelTransport(modelFactory);

		assertThrows(IllegalArgumentException.class,
				() -> transport.call(config, RouteModelOutputProtocol.JSON_OBJECT, Duration.ofSeconds(1),
						Duration.ofSeconds(1), "system", "user"));

		org.mockito.Mockito.verify(modelFactory, org.mockito.Mockito.never()).createRouteModelForProtocol(
				eq(config), any(Duration.class), any(Duration.class), eq(RouteModelOutputProtocol.JSON_OBJECT),
				any(ModelRequestOptionsOverride.class), isNull(), eq(RouteModelProtocol.JSON_SCHEMA));
	}

	@Test
	void routeRejectsUnknownReasoningTokenAccountingBeforeTransport() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDTO config = ModelConfigDTO.builder().provider("stepfun").apiKey("key")
			.baseUrl("https://example.com").modelName("step-1").modelType("CHAT")
			.endpointDialect("STEPFUN_NATIVE").reasoningMode("ENABLED").reasoningLevel("HIGH")
			.maxTokens(2000L).build();
		when(modelFactory.resolveRequestOptions(eq(config), any(ModelRequestOptionsOverride.class), isNull()))
			.thenReturn(new ModelRequestOptionsResolver().resolve(config));
		RouteModelTransport transport = new RouteModelTransport(modelFactory);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> transport.call(config, RouteModelOutputProtocol.JSON_OBJECT, Duration.ofSeconds(1),
						Duration.ofSeconds(1), "system", "user"));

		assertEquals("Route reasoning token accounting must be explicitly supported", error.getMessage());
		org.mockito.Mockito.verify(modelFactory, org.mockito.Mockito.never()).createRouteModelForProtocol(
				eq(config), any(Duration.class), any(Duration.class), eq(RouteModelOutputProtocol.JSON_OBJECT),
				any(ModelRequestOptionsOverride.class), isNull(), eq(RouteModelProtocol.JSON_SCHEMA));
	}

	@Test
	void routeRejectsBudgetThatCannotFitContextWindow() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDTO config = ModelConfigDTO.builder().provider("qwen").apiKey("key")
			.baseUrl("https://example.com").modelName("qwen-plus").modelType("CHAT")
			.endpointDialect("DASHSCOPE_NATIVE").reasoningMode("ENABLED")
			.reasoningBudgetTokens(512L).maxTokens(2000L).contextWindowTokens(700L).build();
		when(modelFactory.resolveRequestOptions(eq(config), any(ModelRequestOptionsOverride.class), isNull()))
			.thenReturn(new ModelRequestOptionsResolver().resolve(config));
		RouteModelTransport transport = new RouteModelTransport(modelFactory);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> transport.call(config, RouteModelOutputProtocol.JSON_OBJECT, Duration.ofSeconds(1),
						Duration.ofSeconds(1), "system", "user"));

		assertEquals("Route model contextWindowTokens must reserve at least 768 completion tokens", error.getMessage());
	}

	@Test
	void separateReasoningBudgetOnlyExpandsContextReserve() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		AtomicReference<ModelRequestOptionsOverride> captured = new AtomicReference<>();
		ModelConfigDTO config = ModelConfigDTO.builder().provider("custom").apiKey("key")
			.baseUrl("https://example.com").modelName("separate-budget-model").modelType("CHAT")
			.maxTokens(300L).contextWindowTokens(1000L).build();
		when(modelFactory.resolveRequestOptions(eq(config), any(ModelRequestOptionsOverride.class), isNull()))
			.thenReturn(resolved(ModelTokenAccounting.SEPARATE_REASONING_BUDGET, 512L));
		when(modelFactory.createRouteModelForProtocol(eq(config), any(Duration.class), any(Duration.class),
				eq(RouteModelOutputProtocol.JSON_OBJECT), any(ModelRequestOptionsOverride.class), isNull(),
				eq(RouteModelProtocol.JSON_SCHEMA))).thenAnswer(invocation -> {
			captured.set(invocation.getArgument(4));
			throw new IllegalStateException("capture");
		});
		RouteModelTransport transport = new RouteModelTransport(modelFactory);

		assertThrows(RouteModelTransportException.class,
				() -> transport.call(config, RouteModelOutputProtocol.JSON_OBJECT, Duration.ofSeconds(1),
						Duration.ofSeconds(1), "system", "user"));

		assertEquals(256L, captured.get().maxOutputTokens());
	}

	@Test
	void explicitNoReasoningProfileUsesOutputOnlyBudget() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		AtomicReference<ModelRequestOptionsOverride> captured = new AtomicReference<>();
		ModelConfigDTO config = ModelConfigDTO.builder().provider("custom").apiKey("key")
			.baseUrl("https://example.com").modelName("output-only-model").modelType("CHAT")
			.capabilityProfile(ModelCapabilityProfile.NO_REASONING.name())
			.maxTokens(300L).contextWindowTokens(300L).build();
		when(modelFactory.resolveRequestOptions(eq(config), any(ModelRequestOptionsOverride.class), isNull()))
			.thenReturn(new ModelRequestOptionsResolver().resolve(config));
		when(modelFactory.createRouteModelForProtocol(eq(config), any(Duration.class), any(Duration.class),
				eq(RouteModelOutputProtocol.JSON_OBJECT), any(ModelRequestOptionsOverride.class), isNull(),
				eq(RouteModelProtocol.JSON_SCHEMA))).thenAnswer(invocation -> {
			captured.set(invocation.getArgument(4));
			throw new IllegalStateException("capture");
		});
		RouteModelTransport transport = new RouteModelTransport(modelFactory);

		assertThrows(RouteModelTransportException.class,
				() -> transport.call(config, RouteModelOutputProtocol.JSON_OBJECT, Duration.ofSeconds(1),
						Duration.ofSeconds(1), "system", "user"));

		assertEquals(256L, captured.get().maxOutputTokens());
	}

	@Test
	void routeRejectsNoneProtocolWithoutOutputOnlyGuarantee() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDTO config = ModelConfigDTO.builder().provider("custom").apiKey("key")
			.baseUrl("https://example.com").modelName("compatible-chat").modelType("CHAT")
			.endpointDialect("OPENAI_COMPATIBLE").reasoningProtocol("AUTO").reasoningMode("AUTO")
			.maxTokens(600L).build();
		when(modelFactory.resolveRequestOptions(eq(config), any(ModelRequestOptionsOverride.class), isNull()))
			.thenReturn(new ModelRequestOptionsResolver().resolve(config));
		RouteModelTransport transport = new RouteModelTransport(modelFactory);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> transport.call(config, RouteModelOutputProtocol.JSON_OBJECT, Duration.ofSeconds(1),
						Duration.ofSeconds(1), "system", "user"));

		assertEquals("Route model without a reasoning protocol requires OUTPUT_ONLY token accounting",
				error.getMessage());
		org.mockito.Mockito.verify(modelFactory, org.mockito.Mockito.never()).createRouteModelForProtocol(
				eq(config), any(Duration.class), any(Duration.class), eq(RouteModelOutputProtocol.JSON_OBJECT),
				any(ModelRequestOptionsOverride.class), isNull(), eq(RouteModelProtocol.JSON_SCHEMA));
	}

	@Test
	void providerLevelReasoningDisableUsesFinalJsonBudget() {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		AtomicReference<ModelRequestOptionsOverride> captured = new AtomicReference<>();
		ModelConfigDTO config = ModelConfigDTO.builder().provider("deepseek").apiKey("key")
			.baseUrl("https://example.com").modelName("deepseek-chat").modelType("CHAT")
			.maxTokens(300L).contextWindowTokens(300L).build();
		ResolvedModelRequestOptions disabled = new ResolvedModelRequestOptions(ModelEndpointDialect.DEEPSEEK_NATIVE,
				ModelReasoningProtocol.THINKING_OBJECT, ModelReasoningMode.DISABLED, null, null, 300,
				ModelTokenLimitMode.MAX_TOKENS, ModelTokenAccounting.UNKNOWN, null, ModelTemperaturePolicy.OMIT,
				ModelStructuredOutputMode.JSON_OBJECT, ModelPreservedReasoningPolicy.DROP, Map.of(), null);
		when(modelFactory.resolveRequestOptions(eq(config), any(ModelRequestOptionsOverride.class), isNull()))
			.thenReturn(disabled);
		when(modelFactory.createRouteModelForProtocol(eq(config), any(Duration.class), any(Duration.class),
				eq(RouteModelOutputProtocol.JSON_OBJECT), any(ModelRequestOptionsOverride.class), isNull(),
				eq(RouteModelProtocol.JSON_SCHEMA))).thenAnswer(invocation -> {
			captured.set(invocation.getArgument(4));
			throw new IllegalStateException("capture");
		});
		RouteModelTransport transport = new RouteModelTransport(modelFactory);

		assertThrows(RouteModelTransportException.class,
				() -> transport.call(config, RouteModelOutputProtocol.JSON_OBJECT, Duration.ofSeconds(1),
						Duration.ofSeconds(1), "system", "user"));

		assertEquals(256L, captured.get().maxOutputTokens());
	}

	private RouteModelTransportException callWithFailure(RuntimeException failure) {
		DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);
		ModelConfigDTO config = ModelConfigDTO.builder().provider("custom").modelName("route-v2")
			.capabilityProfile(ModelCapabilityProfile.NO_REASONING.name()).build();
		when(modelFactory.resolveRequestOptions(eq(config), any(ModelRequestOptionsOverride.class), isNull()))
			.thenReturn(new ModelRequestOptionsResolver().resolve(config));
		when(modelFactory.createRouteModelForProtocol(eq(config), any(Duration.class), any(Duration.class),
				eq(RouteModelOutputProtocol.JSON_OBJECT), any(ModelRequestOptionsOverride.class), isNull(),
				eq(RouteModelProtocol.JSON_SCHEMA))).thenThrow(failure);
		RouteModelTransport transport = new RouteModelTransport(modelFactory);

		return assertThrows(RouteModelTransportException.class,
				() -> transport.call(config, RouteModelOutputProtocol.JSON_OBJECT, Duration.ofSeconds(1),
						Duration.ofSeconds(1), "system", "user"));
	}

	private ResolvedModelRequestOptions resolved(ModelTokenAccounting accounting, Long reasoningBudget) {
		return new ResolvedModelRequestOptions(ModelEndpointDialect.DASHSCOPE_NATIVE,
				ModelReasoningProtocol.ENABLE_THINKING, ModelReasoningMode.ENABLED, null, reasoningBudget, 2000,
				ModelTokenLimitMode.MAX_TOKENS, accounting, 0D, ModelTemperaturePolicy.SEND,
				ModelStructuredOutputMode.JSON_OBJECT, ModelPreservedReasoningPolicy.DROP, Map.of(), null);
	}

}
