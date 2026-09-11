/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.routing.RouteModelTransport.Response;
import com.sn68.agent.dataagent.routing.RouteModelTransportException.FailureKind;
import com.sn68.agent.dataagent.routing.RouteScorer.ScoredCandidate;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteModelResult;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class RouteModelAdapterTest {

	@Test
	void customEndpointProbeFallsBackToPromptJsonAfterStructuredProtocolsAreRejected() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		ModelConfigDTO config = config();
		RouteModelTransportException rejected = new RouteModelTransportException(FailureKind.PROTOCOL_REJECTED,
				"unsupported", null);
		Response response = new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.0}",
				"stop", 1);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any())).thenThrow(rejected);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any())).thenThrow(rejected);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.PROMPT_JSON), any(Duration.class),
				any(Duration.class), any(), any())).thenAnswer(invocation -> probeResponse(invocation.getArgument(5)));

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.SUPPORTED, result.state());
		assertEquals(RouteModelOutputProtocol.PROMPT_JSON, result.protocol());
		InOrder calls = inOrder(transport);
		calls.verify(transport).call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any());
		calls.verify(transport).call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any());
		calls.verify(transport, times(2)).call(eq(config), eq(RouteModelOutputProtocol.PROMPT_JSON), any(Duration.class),
				any(Duration.class), any(), any());
		verifyNoMoreInteractions(transport);
	}

	@Test
	void probeReturnsUnsupportedOnlyWhenEveryProtocolIsRejected() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		ModelConfigDTO config = config();
		RouteModelTransportException rejected = new RouteModelTransportException(FailureKind.PROTOCOL_REJECTED,
				"unsupported", null);
		for (RouteModelOutputProtocol protocol : List.of(RouteModelOutputProtocol.FUNCTION_CALL,
				RouteModelOutputProtocol.STRICT_SCHEMA,
				RouteModelOutputProtocol.JSON_OBJECT, RouteModelOutputProtocol.PROMPT_JSON)) {
			when(transport.call(eq(config), eq(protocol), any(Duration.class), any(Duration.class), any(), any()))
				.thenThrow(rejected);
		}

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.UNSUPPORTED, result.state());
		assertEquals(RouteModelOutputProtocol.NONE, result.protocol());
		assertEquals("ROUTE_MODEL_PROTOCOL_UNSUPPORTED", result.failureCode());
	}

	@Test
	void functionCallingIsProbedBeforeJsonProtocolsWhenEnabled() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		DataAgentProperties properties = new DataAgentProperties();
		properties.getRuntime().getRouting().setFunctionCallingEnabled(true);
		RouteModelAdapter adapter = adapter(transport, properties);
		ModelConfigDTO config = ModelConfigDTO.builder().provider("qwen").baseUrl("https://dashscope.example")
			.modelName("qwen-plus").modelType(ModelType.CHAT.getCode()).endpointDialect("DASHSCOPE_NATIVE").build();
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.FUNCTION_CALL), any(Duration.class),
				any(Duration.class), any(), any())).thenAnswer(invocation -> probeResponse(invocation.getArgument(5)));

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.SUPPORTED, result.state());
		assertEquals(RouteModelOutputProtocol.FUNCTION_CALL, result.protocol());
		verify(transport, times(2)).call(eq(config), eq(RouteModelOutputProtocol.FUNCTION_CALL), any(Duration.class),
				any(Duration.class), any(), any());
		verifyNoMoreInteractions(transport);
	}

	@Test
	void invalidOutputTakesPriorityOverPartialProtocolRejection() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		ModelConfigDTO config = config();
		RouteModelTransportException rejected = new RouteModelTransportException(FailureKind.PROTOCOL_REJECTED,
				"unsupported", null);
		Response invalid = new Response("not-json", "stop", 1);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any())).thenThrow(rejected);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any())).thenReturn(invalid);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.PROMPT_JSON), any(Duration.class),
				any(Duration.class), any(), any())).thenThrow(rejected);

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.UNAVAILABLE, result.state());
		assertEquals("ROUTE_MODEL_INVALID_OUTPUT", result.failureCode());
	}

	@Test
	void probeUsesDedicatedConfiguredTimeouts() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		DataAgentProperties properties = new DataAgentProperties();
		properties.getRuntime().getRouting().setModelProbeTimeout(Duration.ofSeconds(12));
		properties.getRuntime().getRouting().setModelProbeConnectTimeout(Duration.ofSeconds(2));
		RouteModelAdapter adapter = new RouteModelAdapter(transport, new ObjectMapper(), properties);
		ModelConfigDTO config = config();
		Response response = new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.0}",
				"stop", 1);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any())).thenAnswer(invocation -> probeResponse(invocation.getArgument(5)));

		adapter.probe(config);

		ArgumentCaptor<Duration> requestTimeout = ArgumentCaptor.forClass(Duration.class);
		ArgumentCaptor<Duration> connectTimeout = ArgumentCaptor.forClass(Duration.class);
		verify(transport, times(2)).call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), requestTimeout.capture(),
				connectTimeout.capture(), any(), any());
		assertTrue(requestTimeout.getAllValues().stream().allMatch(value -> value.compareTo(Duration.ofSeconds(12)) <= 0));
		assertTrue(connectTimeout.getAllValues().stream().allMatch(value -> value.compareTo(Duration.ofSeconds(2)) <= 0));
	}

	@Test
	void probeFallsBackAfterInvalidStructuredOutput() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		ModelConfigDTO config = config();
		Response invalid = new Response("not-json", "stop", 1);
		Response valid = new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.0}",
				"stop", 1);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any())).thenReturn(invalid);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any())).thenAnswer(invocation -> probeResponse(invocation.getArgument(5)));

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.SUPPORTED, result.state());
		assertEquals(RouteModelOutputProtocol.JSON_OBJECT, result.protocol());
		InOrder calls = inOrder(transport);
		calls.verify(transport).call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any());
		calls.verify(transport, times(2)).call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any());
		verifyNoMoreInteractions(transport);
	}

	@Test
	void directDeepSeekUsesTheFixedProtocolOrder() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		ModelConfigDTO config = ModelConfigDTO.builder()
			.provider("deepseek")
			.baseUrl("https://api.deepseek.com")
			.modelName("deepseek-chat")
			.endpointDialect("DEEPSEEK_NATIVE")
			.modelType(ModelType.CHAT.getCode())
			.build();
		RouteModelTransportException rejected = new RouteModelTransportException(FailureKind.PROTOCOL_REJECTED,
				"unsupported", null);
		Response valid = new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.0}",
				"stop", 1);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any())).thenThrow(rejected);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.PROMPT_JSON), any(Duration.class),
				any(Duration.class), any(), any())).thenAnswer(invocation -> probeResponse(invocation.getArgument(5)));

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.SUPPORTED, result.state());
		assertEquals(RouteModelOutputProtocol.PROMPT_JSON, result.protocol());
		InOrder calls = inOrder(transport);
		calls.verify(transport).call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any());
		calls.verify(transport, times(2)).call(eq(config), eq(RouteModelOutputProtocol.PROMPT_JSON), any(Duration.class),
				any(Duration.class), any(), any());
	}

	@Test
	void qwenProbeDelegatesProviderOptionsToTheTransport() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		ModelConfigDTO config = ModelConfigDTO.builder()
			.provider("qwen")
			.baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
			.modelName("qwen-plus")
			.endpointDialect("DASHSCOPE_NATIVE")
			.modelType(ModelType.CHAT.getCode())
			.build();
		Response valid = new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.0}",
				"stop", 1);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any())).thenAnswer(invocation -> probeResponse(invocation.getArgument(5)));

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.SUPPORTED, result.state());
		assertEquals(RouteModelOutputProtocol.JSON_OBJECT, result.protocol());
		verify(transport, times(2)).call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any());
		verifyNoMoreInteractions(transport);
	}

	@Test
	void kimiK3ProbeDelegatesProviderOptionsToTheTransport() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		ModelConfigDTO config = ModelConfigDTO.builder()
			.provider("kimi")
			.baseUrl("https://api.moonshot.cn/v1")
			.modelName("kimi-k3")
			.endpointDialect("MOONSHOT_NATIVE")
			.modelType(ModelType.CHAT.getCode())
			.build();
		Response valid = new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.0}",
				"stop", 1);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any())).thenAnswer(invocation -> probeResponse(invocation.getArgument(5)));

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.SUPPORTED, result.state());
		assertEquals(RouteModelOutputProtocol.JSON_OBJECT, result.protocol());
		verify(transport, times(2)).call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any());
		verifyNoMoreInteractions(transport);
	}

	@Test
	void openAiCompatibleEndpointUsesFullProtocolOrder() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		ModelConfigDTO config = ModelConfigDTO.builder()
			.provider("openai")
			.baseUrl("https://compatible.example/v1")
			.modelName("compatible-chat")
			.endpointDialect("OPENAI_COMPATIBLE")
			.modelType(ModelType.CHAT.getCode())
			.build();
		RouteModelTransportException rejected = new RouteModelTransportException(FailureKind.PROTOCOL_REJECTED,
				"unsupported", null);
		Response valid = new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.0}",
				"stop", 1);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any())).thenThrow(rejected);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any())).thenThrow(rejected);
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.PROMPT_JSON), any(Duration.class),
				any(Duration.class), any(), any())).thenAnswer(invocation -> probeResponse(invocation.getArgument(5)));

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.SUPPORTED, result.state());
		assertEquals(RouteModelOutputProtocol.PROMPT_JSON, result.protocol());
		InOrder calls = inOrder(transport);
		calls.verify(transport).call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any());
		calls.verify(transport).call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any());
		calls.verify(transport, times(2)).call(eq(config), eq(RouteModelOutputProtocol.PROMPT_JSON), any(Duration.class),
				any(Duration.class), any(), any());
		verifyNoMoreInteractions(transport);
	}

	@Test
	void disambiguateRejectsUnknownCandidateHandle() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		Response response = new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c2\"],\"confidence\":0.9}",
				"stop", 1);
		when(transport.call(eq(config()), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any())).thenReturn(response);

		RouteStageException error = assertThrows(RouteStageException.class,
				() -> adapter.disambiguate(config(), RouteModelOutputProtocol.JSON_OBJECT, context(), candidates(),
						Duration.ofSeconds(1)));

		assertEquals("ROUTE_MODEL_INVALID_OUTPUT", error.reasonCode());
	}

	@Test
	void strictSchemaDefinesTheV2ResultAndStepShape() throws Exception {
		JsonNode schema = new ObjectMapper().readTree(RouteModelProtocol.JSON_SCHEMA);

		assertEquals(false, schema.path("additionalProperties").booleanValue());
		assertEquals(Set.of("decision", "confidence"), textValues(schema.path("required")));
		JsonNode properties = schema.path("properties");
		assertTrue(properties.has("candidates"));
		assertTrue(properties.has("steps"));
		assertTrue(properties.has("clarificationQuestion"));
		JsonNode step = properties.path("steps").path("items");
		assertEquals(false, step.path("additionalProperties").booleanValue());
		assertEquals(Set.of("stepId", "handle", "queryFragment", "expectedOutput", "dependsOn"),
				textValues(step.path("required")));
	}

	@Test
	void disambiguateParsesTheSameV2MultiSelectForEveryProtocol() {
		Response response = multiSelectResponse(
				"{\"stepId\":\"fetch_data\",\"handle\":\"c1\",\"queryFragment\":\"fetch data\","
						+ "\"expectedOutput\":\"raw data\",\"dependsOn\":[]}",
				"{\"stepId\":\"build_summary\",\"handle\":\"c2\",\"queryFragment\":\"build summary\","
						+ "\"expectedOutput\":\"summary\",\"dependsOn\":[\"fetch_data\"]}");
		for (RouteModelOutputProtocol protocol : List.of(RouteModelOutputProtocol.FUNCTION_CALL,
				RouteModelOutputProtocol.STRICT_SCHEMA,
				RouteModelOutputProtocol.JSON_OBJECT, RouteModelOutputProtocol.PROMPT_JSON)) {
			RouteModelTransport transport = mock(RouteModelTransport.class);
			when(transport.call(eq(config()), eq(protocol), any(Duration.class), any(Duration.class), any(), any()))
				.thenReturn(response);

			RouteModelResult result = adapter(transport).disambiguate(config(), protocol, context(), candidates(2),
					Duration.ofSeconds(1));

			assertEquals(RouteDecisionType.MULTI_SELECT, result.decision());
			assertEquals(List.of(candidates(2).get(0).candidate().target(), candidates(2).get(1).candidate().target()),
					result.targets());
			assertEquals(List.of("fetch_data", "build_summary"),
					result.plan().steps().stream().map(step -> step.stepId()).toList());
			assertEquals(List.of(), result.plan().steps().get(0).dependsOn());
			assertEquals(List.of("fetch_data"), result.plan().steps().get(1).dependsOn());
			assertEquals("fetch data", result.plan().steps().get(0).queryFragment());
			assertEquals("summary", result.plan().steps().get(1).expectedOutput());
			verify(transport).call(eq(config()), eq(protocol), any(Duration.class), any(Duration.class), any(), any());
			verifyNoMoreInteractions(transport);
		}
	}

	@Test
	void disambiguateRejectsMissingOrExtraStepFields() {
		List<String> invalidFirstSteps = List.of(
				"{\"handle\":\"c1\",\"queryFragment\":\"fetch data\",\"expectedOutput\":\"raw data\",\"dependsOn\":[]}",
				"{\"stepId\":\"fetch_data\",\"queryFragment\":\"fetch data\",\"expectedOutput\":\"raw data\",\"dependsOn\":[]}",
				"{\"stepId\":\"fetch_data\",\"handle\":\"c1\",\"expectedOutput\":\"raw data\",\"dependsOn\":[]}",
				"{\"stepId\":\"fetch_data\",\"handle\":\"c1\",\"queryFragment\":\"fetch data\",\"dependsOn\":[]}",
				"{\"stepId\":\"fetch_data\",\"handle\":\"c1\",\"queryFragment\":\"fetch data\",\"expectedOutput\":\"raw data\"}",
				"{\"stepId\":\"fetch_data\",\"handle\":\"c1\",\"queryFragment\":\"fetch data\","
						+ "\"expectedOutput\":\"raw data\",\"dependsOn\":[],\"explanation\":\"ignored\"}");
		String validSecondStep = "{\"stepId\":\"build_summary\",\"handle\":\"c2\","
				+ "\"queryFragment\":\"build summary\",\"expectedOutput\":\"summary\","
				+ "\"dependsOn\":[\"fetch_data\"]}";

		for (String invalidFirstStep : invalidFirstSteps) {
			assertInvalidOutput(multiSelectResponse(invalidFirstStep, validSecondStep),
					RouteModelOutputProtocol.JSON_OBJECT, candidates(2));
		}
	}

	@Test
	void disambiguateRejectsInvalidStepIdentityTargetAndDependencies() {
		String firstStep = "{\"stepId\":\"fetch_data\",\"handle\":\"c1\",\"queryFragment\":\"fetch data\","
				+ "\"expectedOutput\":\"raw data\",\"dependsOn\":[]}";
		List<Response> invalidResponses = List.of(
				multiSelectResponse(firstStep,
						"{\"stepId\":\"fetch_data\",\"handle\":\"c2\",\"queryFragment\":\"build summary\","
								+ "\"expectedOutput\":\"summary\",\"dependsOn\":[]}"),
				multiSelectResponse(firstStep,
						"{\"stepId\":\"build_summary\",\"handle\":\"c3\",\"queryFragment\":\"build summary\","
								+ "\"expectedOutput\":\"summary\",\"dependsOn\":[\"fetch_data\"]}"),
				multiSelectResponse(
						"{\"stepId\":\"fetch_data\",\"handle\":\"c1\",\"queryFragment\":\"fetch data\","
								+ "\"expectedOutput\":\"raw data\",\"dependsOn\":[\"build_summary\"]}",
						"{\"stepId\":\"build_summary\",\"handle\":\"c2\",\"queryFragment\":\"build summary\","
								+ "\"expectedOutput\":\"summary\",\"dependsOn\":[]}"),
				multiSelectResponse(firstStep,
						"{\"stepId\":\"build_summary\",\"handle\":\"c2\",\"queryFragment\":\"build summary\","
								+ "\"expectedOutput\":\"summary\",\"dependsOn\":[\"missing\"]}"));

		for (Response response : invalidResponses) {
			assertInvalidOutput(response, RouteModelOutputProtocol.JSON_OBJECT, candidates(2));
		}
	}

	@Test
	void clarifySelectAndNoMatchAcceptDecisionSpecificOptionalFields() {
		for (RouteModelOutputProtocol protocol : List.of(RouteModelOutputProtocol.STRICT_SCHEMA,
				RouteModelOutputProtocol.JSON_OBJECT, RouteModelOutputProtocol.PROMPT_JSON)) {
			RouteModelResult clarify = disambiguate(protocol, new Response(
					"{\"decision\":\"CLARIFY\",\"confidence\":0.4,"
							+ "\"clarificationQuestion\":\"Which dataset should be used?\"}", "stop", 1),
					candidates());
			RouteModelResult select = disambiguate(protocol,
					new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":0.9}",
							"stop", 1),
					candidates());
			RouteModelResult noMatch = disambiguate(protocol,
					new Response("{\"decision\":\"NO_MATCH\",\"confidence\":0.1}", "stop", 1), candidates());

			assertEquals("Which dataset should be used?", clarify.clarificationQuestion());
			assertEquals(RouteDecisionType.SELECT, select.decision());
			assertEquals(1, select.targets().size());
			assertEquals(RouteDecisionType.NO_MATCH, noMatch.decision());
			assertTrue(noMatch.targets().isEmpty());
		}
	}

	@Test
	void promptJsonDescribesTheCompleteV2ContractWithoutRuntimeFallback() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		Response response = new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":0.9}",
				"stop", 1);
		when(transport.call(eq(config()), eq(RouteModelOutputProtocol.PROMPT_JSON), any(Duration.class),
				any(Duration.class), any(), any())).thenReturn(response);

		adapter(transport).disambiguate(config(), RouteModelOutputProtocol.PROMPT_JSON, context(), candidates(),
				Duration.ofSeconds(1));

		ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
		verify(transport).call(eq(config()), eq(RouteModelOutputProtocol.PROMPT_JSON), any(Duration.class),
				any(Duration.class), systemPrompt.capture(), any());
		for (String field : List.of("decision", "candidates", "steps", "confidence", "clarificationQuestion",
				"stepId", "handle", "queryFragment", "expectedOutput", "dependsOn")) {
			assertTrue(systemPrompt.getValue().contains(field), () -> "Missing V2 field in prompt: " + field);
		}
		assertTrue(systemPrompt.getValue().contains("ordered or dependent"));
		assertFalse(systemPrompt.getValue().contains("explicit independent multiple intents"));
		verifyNoMoreInteractions(transport);
	}

	@Test
	void probeClassifiesTransportTimeoutAsUnavailable() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		ModelConfigDTO config = config();
		RouteModelTransportException timeout = new RouteModelTransportException(FailureKind.UNAVAILABLE, "timeout",
				new SocketTimeoutException("read timed out"));
		when(transport.call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any())).thenThrow(timeout);

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.UNAVAILABLE, result.state());
		assertEquals("ROUTE_MODEL_UNAVAILABLE", result.failureCode());
		verify(transport).call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any());
		verifyNoMoreInteractions(transport);
	}

	@Test
	void probeReturnsUnavailableWhenAllProtocolsReturnInvalidJson() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		ModelConfigDTO config = config();
		Response invalid = new Response("not-json", "stop", 1);
		for (RouteModelOutputProtocol protocol : List.of(RouteModelOutputProtocol.STRICT_SCHEMA,
				RouteModelOutputProtocol.JSON_OBJECT, RouteModelOutputProtocol.PROMPT_JSON)) {
			when(transport.call(eq(config), eq(protocol), any(Duration.class), any(Duration.class), any(), any()))
				.thenReturn(invalid);
		}

		RouteModelProbeResult result = adapter.probe(config);

		assertEquals(RouteCapabilityState.UNAVAILABLE, result.state());
		assertEquals("ROUTE_MODEL_INVALID_OUTPUT", result.failureCode());
		verify(transport).call(eq(config), eq(RouteModelOutputProtocol.STRICT_SCHEMA), any(Duration.class),
				any(Duration.class), any(), any());
		verify(transport).call(eq(config), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any());
		verify(transport).call(eq(config), eq(RouteModelOutputProtocol.PROMPT_JSON), any(Duration.class),
				any(Duration.class), any(), any());
	}

	@Test
	void disambiguateRejectsTruncatedResponse() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		Response response = new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":0.9}",
				"length", 1);
		when(transport.call(eq(config()), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any())).thenReturn(response);

		RouteStageException error = assertThrows(RouteStageException.class,
				() -> adapter.disambiguate(config(), RouteModelOutputProtocol.JSON_OBJECT, context(), candidates(),
						Duration.ofSeconds(1)));

		assertEquals("ROUTE_MODEL_INVALID_OUTPUT", error.reasonCode());
	}

	@Test
	void disambiguateRejectsExtraFields() {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		Response response = new Response(
				"{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":0.9,\"explanation\":\"ignored\"}",
				"stop", 1);
		when(transport.call(eq(config()), eq(RouteModelOutputProtocol.JSON_OBJECT), any(Duration.class),
				any(Duration.class), any(), any())).thenReturn(response);

		RouteStageException error = assertThrows(RouteStageException.class,
				() -> adapter.disambiguate(config(), RouteModelOutputProtocol.JSON_OBJECT, context(), candidates(),
						Duration.ofSeconds(1)));

		assertEquals("ROUTE_MODEL_INVALID_OUTPUT", error.reasonCode());
	}

	@Test
	void disambiguateRejectsDuplicateKeysAndTrailingJson() {
		assertInvalidOutput(new Response(
				"{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":0.9,\"confidence\":0.8}",
				"stop", 1), RouteModelOutputProtocol.JSON_OBJECT, candidates());
		assertInvalidOutput(new Response(
				"{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":0.9} {}", "stop", 1),
				RouteModelOutputProtocol.PROMPT_JSON, candidates());
	}

	@Test
	void disambiguateRejectsFencedJsonForEveryProtocol() {
		Response response = new Response(
				"```json\n{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":0.9}\n```", "stop", 1);
		for (RouteModelOutputProtocol protocol : List.of(RouteModelOutputProtocol.STRICT_SCHEMA,
				RouteModelOutputProtocol.JSON_OBJECT, RouteModelOutputProtocol.PROMPT_JSON)) {
			assertInvalidOutput(response, protocol, candidates());
		}
	}

	@Test
	void disambiguateRejectsArbitraryTextAroundPromptJson() {
		Response response = new Response(
				"Result:\n```json\n{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":0.9}\n```", "stop", 1);

		assertInvalidOutput(response, RouteModelOutputProtocol.PROMPT_JSON, candidates());
	}

	@Test
	void disambiguateRejectsMoreThanFiveCandidatesForEveryProtocol() {
		Response response = new Response(
				"{\"decision\":\"MULTI_SELECT\",\"candidates\":[\"c1\",\"c2\",\"c3\",\"c4\",\"c5\",\"c6\"],\"confidence\":0.9}",
				"stop", 1);
		for (RouteModelOutputProtocol protocol : List.of(RouteModelOutputProtocol.STRICT_SCHEMA,
				RouteModelOutputProtocol.JSON_OBJECT, RouteModelOutputProtocol.PROMPT_JSON)) {
			assertInvalidOutput(response, protocol, candidates(6));
		}
	}

	@Test
	void disambiguateEnforcesConfidenceAndDecisionCardinality() {
		assertInvalidOutput(
				new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.01}", "stop", 1),
				RouteModelOutputProtocol.JSON_OBJECT, candidates());
		assertInvalidOutput(new Response(
				"{\"decision\":\"MULTI_SELECT\",\"candidates\":[\"c1\"],\"confidence\":0.9}", "stop", 1),
				RouteModelOutputProtocol.JSON_OBJECT, candidates());
	}

	@Test
	void disambiguateRejectsMultiSelectWithoutStepPlan() {
		assertInvalidOutput(new Response(
				"{\"decision\":\"MULTI_SELECT\",\"candidates\":[\"c1\",\"c2\"],\"confidence\":0.9}",
				"stop", 1), RouteModelOutputProtocol.JSON_OBJECT, candidates(2));
	}

	private Set<String> textValues(JsonNode array) {
		Set<String> result = new java.util.LinkedHashSet<>();
		array.forEach(value -> result.add(value.textValue()));
		return result;
	}

	private Response multiSelectResponse(String firstStep, String secondStep) {
		return new Response("{\"decision\":\"MULTI_SELECT\",\"steps\":[" + firstStep + "," + secondStep
				+ "],\"confidence\":0.9}", "stop", 1);
	}

	private RouteModelResult disambiguate(RouteModelOutputProtocol protocol, Response response,
			List<ScoredCandidate> routeCandidates) {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		when(transport.call(eq(config()), eq(protocol), any(Duration.class), any(Duration.class), any(), any()))
			.thenReturn(response);
		return adapter(transport).disambiguate(config(), protocol, context(), routeCandidates, Duration.ofSeconds(1));
	}

	private void assertInvalidOutput(Response response, RouteModelOutputProtocol protocol,
			List<ScoredCandidate> routeCandidates) {
		RouteModelTransport transport = mock(RouteModelTransport.class);
		RouteModelAdapter adapter = adapter(transport);
		when(transport.call(eq(config()), eq(protocol), any(Duration.class), any(Duration.class), any(), any()))
			.thenReturn(response);

		RouteStageException error = assertThrows(RouteStageException.class,
				() -> adapter.disambiguate(config(), protocol, context(), routeCandidates, Duration.ofSeconds(1)));

		assertEquals("ROUTE_MODEL_INVALID_OUTPUT", error.reasonCode());
	}

	private ModelConfigDTO config() {
		return ModelConfigDTO.builder().id(1L).provider("custom").baseUrl("https://route.example")
			.modelName("route-v2").modelType(ModelType.CHAT.getCode()).endpointDialect("CUSTOM").build();
	}

	private Response probeResponse(String prompt) {
		if (prompt != null && prompt.contains("first select c1")) {
			return new Response("{\"decision\":\"MULTI_SELECT\",\"steps\":["
					+ "{\"stepId\":\"first\",\"handle\":\"c1\",\"queryFragment\":\"first\",\"expectedOutput\":\"first result\",\"dependsOn\":[]},"
					+ "{\"stepId\":\"second\",\"handle\":\"c2\",\"queryFragment\":\"second\",\"expectedOutput\":\"second result\",\"dependsOn\":[\"first\"]}"
					+ "],\"confidence\":1.0}", "stop", 1);
		}
		return new Response("{\"decision\":\"SELECT\",\"candidates\":[\"c1\"],\"confidence\":1.0}", "stop", 1);
	}

	private RouteContext context() {
		return new RouteContext("tenant-1", 10L, "DATA_ANALYSIS", 100L, "user-1", "request-1", "query", null,
				null, null, Instant.now().plusSeconds(3), 1);
	}

	private List<ScoredCandidate> candidates() {
		return candidates(1);
	}

	private List<ScoredCandidate> candidates(int count) {
		List<ScoredCandidate> result = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			long offset = index;
			RouteCandidate candidate = new RouteCandidate(
					new RouteTargetRef(RouteTargetType.SKILL, 30L + offset, 31L + offset, 32L + offset), "tenant-1",
					10L, "query skill", "query skill", "QUERY", "REACT", RouteRules.empty(), RouteRisk.READ_ONLY, 0,
					1L, 40L + offset, "checksum", "embedding-v1");
			result.add(new ScoredCandidate(candidate, 70, false, false, List.of("phrase:query")));
		}
		return result;
	}

	private RouteModelAdapter adapter(RouteModelTransport transport) {
		return adapter(transport, new DataAgentProperties());
	}

	private RouteModelAdapter adapter(RouteModelTransport transport, DataAgentProperties properties) {
		return new RouteModelAdapter(transport, new ObjectMapper(), properties);
	}

}
