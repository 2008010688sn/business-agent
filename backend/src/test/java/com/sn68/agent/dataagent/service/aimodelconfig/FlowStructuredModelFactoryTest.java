/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.aimodelconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelCapabilityProfile;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.flow.FlowPatchSubmissionToolCallback;
import com.sn68.agent.dataagent.flow.FlowStructuredOutputProtocol;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.mock.env.MockEnvironment;

class FlowStructuredModelFactoryTest {

	private static final String PATCH_SCHEMA = "{\"type\":\"object\",\"properties\":{\"set\":{\"type\":\"object\",\"additionalProperties\":false}},\"required\":[\"set\"],\"additionalProperties\":false}";

	@Test
	void functionCallingUsesOnlyTheNonExecutingFlowPatchTool() {
		OpenAiChatOptions options = options(FlowStructuredOutputProtocol.FUNCTION_CALL);

		assertNull(options.getResponseFormat());
		assertEquals(1, options.getToolCallbacks().size());
		assertEquals(FlowPatchSubmissionToolCallback.NAME, options.getToolCallbacks().get(0).getToolDefinition().name());
		assertEquals(FlowPatchSubmissionToolCallback.NAME,
				new ObjectMapper().valueToTree(options.getToolChoice()).path("function").path("name").asText());
		assertEquals(Boolean.FALSE, options.getParallelToolCalls());
		assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
		assertThrows(IllegalStateException.class, () -> options.getToolCallbacks().get(0).call("{}"));
	}

	@Test
	void openaiCompatibleDisablesReasoningAndSendsTemperatureZero() {
		OpenAiChatOptions options = options(compatibleConfig(), FlowStructuredOutputProtocol.FUNCTION_CALL);

		assertEquals(0D, options.getTemperature().doubleValue());
		assertNull(options.getReasoningEffort());
		assertEquals(1, options.getToolCallbacks().size());
	}

	@Test
	void stepFunAutoCanBuildFunctionCallingWithoutDisablingReasoning() {
		ModelConfigDTO config = nativeConfig("step-3.7-flash", ModelEndpointDialect.STEPFUN_NATIVE);
		config.setCapabilityProfile(ModelCapabilityProfile.AUTO.name());
		config.setReasoningMode(ModelReasoningMode.AUTO.name());
		OpenAiChatOptions options = options(config, FlowStructuredOutputProtocol.FUNCTION_CALL);

		assertEquals(0D, options.getTemperature().doubleValue());
		assertEquals(FlowPatchSubmissionToolCallback.NAME, options.getToolCallbacks().get(0).getToolDefinition().name());
	}

	@Test
	void stepFunReasoningEnabledCanBuildFunctionCalling() {
		ModelConfigDTO config = nativeConfig("step-3.7-flash", ModelEndpointDialect.STEPFUN_NATIVE);
		config.setCapabilityProfile(ModelCapabilityProfile.STEPFUN_REASONING.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());
		OpenAiChatOptions options = options(config, FlowStructuredOutputProtocol.FUNCTION_CALL);

		assertEquals("high", options.getReasoningEffort());
		assertEquals(1, options.getToolCallbacks().size());
	}

	@Test
	void qwenThinkingOnlyCanBuildFunctionCalling() {
		ModelConfigDTO config = nativeConfig("qwen-plus", ModelEndpointDialect.DASHSCOPE_NATIVE);
		config.setCapabilityProfile(ModelCapabilityProfile.QWEN_THINKING_ONLY.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		OpenAiChatOptions options = options(config, FlowStructuredOutputProtocol.FUNCTION_CALL);

		assertEquals(1, options.getToolCallbacks().size());
		assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
	}

	@Test
	void kimiK3ReasoningCanBuildFunctionCalling() {
		ModelConfigDTO config = nativeConfig("kimi-k2", ModelEndpointDialect.MOONSHOT_NATIVE);
		config.setCapabilityProfile(ModelCapabilityProfile.KIMI_K3_REASONING.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());
		OpenAiChatOptions options = options(config, FlowStructuredOutputProtocol.FUNCTION_CALL);

		assertEquals("high", options.getReasoningEffort());
		assertNull(options.getTemperature());
		assertEquals(1, options.getToolCallbacks().size());
	}

	@Test
	void customDialectCanBuildJsonObjectProtocol() {
		ModelConfigDTO config = ModelConfigDTO.builder().provider("custom").baseUrl("https://example.com")
			.modelName("flow-model").modelType("CHAT").endpointDialect(ModelEndpointDialect.CUSTOM.name()).build();
		OpenAiChatOptions options = options(config, FlowStructuredOutputProtocol.JSON_OBJECT);

		assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
	}

	@Test
	void schemaAndJsonObjectProtocolsUseOnlyTheirNegotiatedResponseFormats() {
		OpenAiChatOptions strict = options(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA);
		OpenAiChatOptions jsonObject = options(FlowStructuredOutputProtocol.JSON_OBJECT);

		assertEquals(ResponseFormat.Type.JSON_SCHEMA, strict.getResponseFormat().getType());
		assertEquals("flow_patch", strict.getResponseFormat().getJsonSchema().getName());
		assertEquals(ResponseFormat.Type.JSON_OBJECT, jsonObject.getResponseFormat().getType());
		assertFalse(strict.getStreamUsage());
		assertFalse(jsonObject.getStreamUsage());
	}

	private OpenAiChatOptions options(FlowStructuredOutputProtocol protocol) {
		return options(compatibleConfig(), protocol);
	}

	private OpenAiChatOptions options(ModelConfigDTO config, FlowStructuredOutputProtocol protocol) {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		FlowStructuredModel model = factory.createFlowStructuredModel(config, Duration.ofSeconds(8), 512L,
				PATCH_SCHEMA, protocol);
		return (OpenAiChatOptions) ((OpenAiChatModel) model.model()).getDefaultOptions();
	}

	private ModelConfigDTO compatibleConfig() {
		return ModelConfigDTO.builder().provider("custom").baseUrl("https://example.com")
			.modelName("flow-model").modelType("CHAT").endpointDialect(ModelEndpointDialect.OPENAI_COMPATIBLE.name())
			.build();
	}

	private ModelConfigDTO nativeConfig(String modelName, ModelEndpointDialect dialect) {
		return ModelConfigDTO.builder()
			.id(1L)
			.provider("custom")
			.apiKey("secret")
			.baseUrl("https://example.com")
			.modelName(modelName)
			.modelType("CHAT")
			.endpointDialect(dialect.name())
			.maxTokens(2000L)
			.build();
	}

}
