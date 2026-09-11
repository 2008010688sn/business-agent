/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.DataAgentModelStructuredCapability;
import com.sn68.agent.dataagent.repository.DataAgentModelStructuredCapabilityMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FlowStructuredCapabilityServiceTest {

	@Test
	void enabledThinkingSkipsToolChoiceAndJsonSchemaProtocols() {
		Fixture fixture = new Fixture();
		fixture.modelConfig.setReasoningMode("ENABLED");
		when(fixture.mapper.findCurrent(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(List.of());

		assertEquals(List.of(FlowStructuredOutputProtocol.JSON_OBJECT),
				fixture.service.candidateProtocols(fixture.modelConfig));
	}

	@Test
	void uncachedModelStartsWithFunctionCalling() {
		Fixture fixture = new Fixture();
		when(fixture.mapper.findCurrent(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(List.of());

		assertEquals(List.of(FlowStructuredOutputProtocol.FUNCTION_CALL,
				FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA, FlowStructuredOutputProtocol.JSON_OBJECT),
				fixture.service.candidateProtocols(fixture.modelConfig));
	}

	@Test
	void cachedSuccessIsPreferredAndKnownUnsupportedIsSkipped() {
		Fixture fixture = new Fixture();
		when(fixture.mapper.findCurrent(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(List.of(
				capability(FlowStructuredOutputProtocol.FUNCTION_CALL, StructuredCapabilityState.UNSUPPORTED),
				capability(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA, StructuredCapabilityState.SUPPORTED)));

		assertEquals(List.of(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
				FlowStructuredOutputProtocol.JSON_OBJECT), fixture.service.candidateProtocols(fixture.modelConfig));
	}

	@Test
	void rejectedProtocolIsSkippedEvenIfDatabaseMissesTheRow() {
		Fixture fixture = new Fixture();
		when(fixture.mapper.findCurrent(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(List.of());

		fixture.service.recordProtocolRejected(fixture.modelConfig, FlowStructuredOutputProtocol.FUNCTION_CALL);

		assertEquals(List.of(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA, FlowStructuredOutputProtocol.JSON_OBJECT),
				fixture.service.candidateProtocols(fixture.modelConfig));
	}

	@Test
	void actualSuccessCreatesTheFlowOnlyCacheRow() {
		Fixture fixture = new Fixture();
		when(fixture.mapper.findCurrent(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(List.of());

		fixture.service.recordSuccess(fixture.modelConfig, FlowStructuredOutputProtocol.FUNCTION_CALL);

		ArgumentCaptor<DataAgentModelStructuredCapability> captor = ArgumentCaptor
			.forClass(DataAgentModelStructuredCapability.class);
		verify(fixture.mapper).insert(captor.capture());
		assertEquals(StructuredCapabilityState.SUPPORTED.name(), captor.getValue().getState());
		assertEquals(FlowStructuredOutputProtocol.FUNCTION_CALL.name(), captor.getValue().getProtocol());
		assertEquals(StructuredTaskProfile.FLOW_PATCH_V1.name(), captor.getValue().getTaskProfile());
		assertEquals("flow-runtime-negotiation/v1", captor.getValue().getProbeVersion());
	}

	private static DataAgentModelStructuredCapability capability(FlowStructuredOutputProtocol protocol,
			StructuredCapabilityState state) {
		return DataAgentModelStructuredCapability.builder().protocol(protocol.name()).state(state.name()).build();
	}

	private static final class Fixture {

		private final DataAgentModelStructuredCapabilityMapper mapper = mock(DataAgentModelStructuredCapabilityMapper.class);

		private final ModelConfigDTO modelConfig = ModelConfigDTO.builder().id(7L).provider("test")
			.baseUrl("https://example.com").modelName("test-model").modelType("CHAT").build();

		private final FlowStructuredCapabilityService service = new FlowStructuredCapabilityService(mapper,
				new FlowStructuredCapabilityFingerprint(), new FlowSchemaCompiler(new ObjectMapper()));

	}

}
