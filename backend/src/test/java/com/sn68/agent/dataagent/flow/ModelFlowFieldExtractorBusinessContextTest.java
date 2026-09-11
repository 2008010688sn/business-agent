/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.FlowStructuredModel;
import com.sn68.agent.dataagent.service.tokenusage.AgentStructuredModelCallResult;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageContext;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageService;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;

class ModelFlowFieldExtractorBusinessContextTest {

	@Test
	void functionCallingReadsThePatchWithoutExecutingATool() {
		Fixture fixture = new Fixture();
		when(fixture.capabilityService.candidateProtocols(fixture.modelConfig))
			.thenReturn(List.of(FlowStructuredOutputProtocol.FUNCTION_CALL));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.FUNCTION_CALL)))
			.thenReturn(new FlowStructuredModel(fixture.chatModel, FlowStructuredOutputProtocol.FUNCTION_CALL));
		AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function",
				FlowPatchSubmissionToolCallback.NAME, "{\"set\":{\"companyName\":\"Acme\"}}");
		when(fixture.tokenUsageService.callAndRecordStructured(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(result("", List.of(call), "stop"));

		Map<String, Object> extracted = fixture.extractor.extract(fixture.request(), fixture.modelConfig, fixture.schema(),
				"extract order fields");

		assertEquals(Map.of("companyName", "Acme"), extracted);
		verify(fixture.capabilityService).recordSuccess(fixture.modelConfig,
				FlowStructuredOutputProtocol.FUNCTION_CALL);
		verify(fixture.modelFactory, never()).createFlowExtractionModel(any(), any(), anyLong(), anyString());
	}

	@Test
	void explicitProtocolRejectionFallsBackInTheSameRequest() {
		Fixture fixture = new Fixture();
		when(fixture.capabilityService.candidateProtocols(fixture.modelConfig)).thenReturn(List.of(
				FlowStructuredOutputProtocol.FUNCTION_CALL, FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.FUNCTION_CALL)))
			.thenThrow(new FlowExtractionException(FlowExtractionException.PROTOCOL_INCOMPATIBLE, "unsupported tools", null));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA)))
			.thenReturn(new FlowStructuredModel(fixture.chatModel, FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA));
		when(fixture.tokenUsageService.callAndRecordStructured(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(result("{\"set\":{\"companyName\":\"Acme\"}}", List.of(), "stop"));

		Map<String, Object> extracted = fixture.extractor.extract(fixture.request(), fixture.modelConfig, fixture.schema(),
				"extract order fields");

		assertEquals(Map.of("companyName", "Acme"), extracted);
		verify(fixture.capabilityService).recordProtocolRejected(fixture.modelConfig,
				FlowStructuredOutputProtocol.FUNCTION_CALL);
		verify(fixture.capabilityService).recordSuccess(fixture.modelConfig,
				FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA);
	}

	@Test
	void functionCallingWithoutToolCallFallsBackToJsonSchema() {
		Fixture fixture = new Fixture();
		when(fixture.capabilityService.candidateProtocols(fixture.modelConfig)).thenReturn(List.of(
				FlowStructuredOutputProtocol.FUNCTION_CALL, FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.FUNCTION_CALL)))
			.thenReturn(new FlowStructuredModel(fixture.chatModel, FlowStructuredOutputProtocol.FUNCTION_CALL));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA)))
			.thenReturn(new FlowStructuredModel(fixture.chatModel, FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA));
		when(fixture.tokenUsageService.callAndRecordStructured(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(result("{\"set\":{\"companyName\":\"Acme\"}}", List.of(), "stop"))
			.thenReturn(result("{\"set\":{\"companyName\":\"Acme\"}}", List.of(), "stop"));

		Map<String, Object> extracted = fixture.extractor.extract(fixture.request(), fixture.modelConfig, fixture.schema(),
				"extract order fields");

		assertEquals(Map.of("companyName", "Acme"), extracted);
		verify(fixture.capabilityService).recordProtocolRejected(fixture.modelConfig,
				FlowStructuredOutputProtocol.FUNCTION_CALL);
		verify(fixture.capabilityService).recordSuccess(fixture.modelConfig,
				FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA);
	}

	@Test
	void factoryConfigErrorFallsBackToNextProtocol() {
		Fixture fixture = new Fixture();
		when(fixture.capabilityService.candidateProtocols(fixture.modelConfig)).thenReturn(List.of(
				FlowStructuredOutputProtocol.FUNCTION_CALL, FlowStructuredOutputProtocol.JSON_OBJECT));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.FUNCTION_CALL)))
			.thenThrow(new IllegalArgumentException("Configured model cannot enforce the FLOW structured response protocol"));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.JSON_OBJECT)))
			.thenReturn(new FlowStructuredModel(fixture.chatModel, FlowStructuredOutputProtocol.JSON_OBJECT));
		when(fixture.tokenUsageService.callAndRecordStructured(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(result("{\"set\":{\"companyName\":\"Acme\"}}", List.of(), "stop"));

		Map<String, Object> extracted = fixture.extractor.extract(fixture.request(), fixture.modelConfig, fixture.schema(),
				"extract order fields");

		assertEquals(Map.of("companyName", "Acme"), extracted);
		verify(fixture.capabilityService, never()).recordProtocolRejected(eq(fixture.modelConfig),
				eq(FlowStructuredOutputProtocol.FUNCTION_CALL));
		verify(fixture.capabilityService).recordSuccess(fixture.modelConfig,
				FlowStructuredOutputProtocol.JSON_OBJECT);
	}

	@Test
	void timeoutFallsBackToNextProtocol() {
		Fixture fixture = new Fixture();
		when(fixture.capabilityService.candidateProtocols(fixture.modelConfig)).thenReturn(List.of(
				FlowStructuredOutputProtocol.FUNCTION_CALL, FlowStructuredOutputProtocol.JSON_OBJECT));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.FUNCTION_CALL)))
			.thenReturn(new FlowStructuredModel(fixture.chatModel, FlowStructuredOutputProtocol.FUNCTION_CALL));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.JSON_OBJECT)))
			.thenReturn(new FlowStructuredModel(fixture.chatModel, FlowStructuredOutputProtocol.JSON_OBJECT));
		when(fixture.tokenUsageService.callAndRecordStructured(eq(fixture.chatModel), anyString(), any()))
			.thenThrow(new FlowExtractionException(FlowExtractionException.TIMEOUT, "FLOW extraction timed out", null))
			.thenReturn(result("{\"set\":{\"companyName\":\"Acme\"}}", List.of(), "stop"));

		Map<String, Object> extracted = fixture.extractor.extract(fixture.request(), fixture.modelConfig,
				fixture.schema(), "extract");

		assertEquals(Map.of("companyName", "Acme"), extracted);
		verify(fixture.capabilityService).recordSuccess(fixture.modelConfig,
				FlowStructuredOutputProtocol.JSON_OBJECT);
	}

	@Test
	void malformedOutputDoesNotMarkAProtocolAsUnsupported() {
		Fixture fixture = new Fixture();
		when(fixture.capabilityService.candidateProtocols(fixture.modelConfig))
			.thenReturn(List.of(FlowStructuredOutputProtocol.FUNCTION_CALL));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.FUNCTION_CALL)))
			.thenReturn(new FlowStructuredModel(fixture.chatModel, FlowStructuredOutputProtocol.FUNCTION_CALL));
		when(fixture.tokenUsageService.callAndRecordStructured(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(result("{\"set\":{\"companyName\":\"Acme\"}}", List.of(), "stop"));

		FlowExtractionException error = assertThrows(FlowExtractionException.class,
				() -> fixture.extractor.extract(fixture.request(), fixture.modelConfig, fixture.schema(), "extract"));

		assertEquals(FlowExtractionException.INVALID_RESPONSE, error.errorCode());
		verify(fixture.capabilityService, never()).recordProtocolRejected(any(), any());
	}

	@Test
	void onlyLengthFinishReasonMarksAResponseAsTruncated() {
		Fixture fixture = new Fixture();
		when(fixture.capabilityService.candidateProtocols(fixture.modelConfig))
			.thenReturn(List.of(FlowStructuredOutputProtocol.JSON_OBJECT));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.JSON_OBJECT)))
			.thenReturn(new FlowStructuredModel(fixture.chatModel, FlowStructuredOutputProtocol.JSON_OBJECT));
		when(fixture.tokenUsageService.callAndRecordStructured(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(result("{\"set\":{\"companyName\":\"Acme\"}}", List.of(), null));

		Map<String, Object> extracted = fixture.extractor.extract(fixture.request(), fixture.modelConfig, fixture.schema(),
				"extract");

		assertEquals(Map.of("companyName", "Acme"), extracted);
	}

	@Test
	void lengthFinishReasonIsTruncationTechnicalFailureEvenWithParsableJson() {
		Fixture fixture = new Fixture();
		when(fixture.capabilityService.candidateProtocols(fixture.modelConfig))
			.thenReturn(List.of(FlowStructuredOutputProtocol.JSON_OBJECT));
		when(fixture.modelFactory.createFlowStructuredModel(any(), any(Duration.class), anyLong(), anyString(),
				eq(FlowStructuredOutputProtocol.JSON_OBJECT)))
			.thenReturn(new FlowStructuredModel(fixture.chatModel, FlowStructuredOutputProtocol.JSON_OBJECT));
		// 即便截断后的文本恰好可解析，finishReason=length 也必须按技术失败上抛，不允许当作结果继续。
		when(fixture.tokenUsageService.callAndRecordStructured(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(result("{\"set\":{\"companyName\":\"Acme\"}}", List.of(), "length"));

		FlowExtractionException error = assertThrows(FlowExtractionException.class,
				() -> fixture.extractor.extract(fixture.request(), fixture.modelConfig, fixture.schema(), "extract"));

		assertEquals(FlowExtractionException.TRUNCATED, error.errorCode());
	}

	private static AgentStructuredModelCallResult result(String text, List<AssistantMessage.ToolCall> calls,
			String finishReason) {
		return new AgentStructuredModelCallResult(null, text, calls, finishReason, 20L, 30L, 50L, "ACTUAL", 8L);
	}

	private static final class Fixture {

		private final DynamicModelFactory modelFactory = mock(DynamicModelFactory.class);

		private final AgentTokenUsageService tokenUsageService = mock(AgentTokenUsageService.class);

		private final AgentTemporalService temporalService = mock(AgentTemporalService.class);

		private final FlowStructuredCapabilityService capabilityService = mock(FlowStructuredCapabilityService.class);

		private final ChatModel chatModel = mock(ChatModel.class);

		private final ModelConfigDTO modelConfig = ModelConfigDTO.builder().id(7L).provider("test")
			.baseUrl("https://example.com").modelName("test-model").modelType("CHAT").maxTokens(8192L)
			.contextWindowTokens(32768L).build();

		private final ModelFlowFieldExtractor extractor = new ModelFlowFieldExtractor(modelFactory, tokenUsageService,
				new ObjectMapper(), new DataAgentProperties(), temporalService, capabilityService,
				new FlowSchemaCompiler(new ObjectMapper()), new FlowStructuredPatchDecoder(new ObjectMapper(),
						new FlowSchemaValidator()), new FlowExtractionBudgetPlanner());

		private Fixture() {
			when(tokenUsageService.buildContext(any(), any(), anyString()))
				.thenReturn(AgentTokenUsageContext.builder().maxTokens(8192L).build());
			when(temporalService.promptBlock(any())).thenReturn("trusted-time");
		}

		private AgentRequest request() {
			return AgentRequest.builder().query("customer is Acme and requires delivery").build();
		}

		private Map<String, Object> schema() {
			return Map.of("type", "object", "properties", Map.of("companyName", Map.of("type", "string")));
		}

	}

}
