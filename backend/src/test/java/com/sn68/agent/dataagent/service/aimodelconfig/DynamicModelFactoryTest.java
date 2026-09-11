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
package com.sn68.agent.dataagent.service.aimodelconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.ModelCapabilityDescriptorResp;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelCapabilityProfile;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelTemperaturePolicy;
import com.sn68.agent.dataagent.enums.ModelTokenLimitMode;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.routing.RouteModelOutputProtocol;
import com.sn68.agent.dataagent.routing.RouteModelProtocol;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsOverride;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestCapabilities;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsResolver;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ResolvedModelRequestOptions;
import java.lang.reflect.Field;
import java.time.Duration;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.retry.support.RetryTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class DynamicModelFactoryTest {

	@Test
	void createChatModel_reusesSameInstanceForSameConfig() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("qwen-max");

		ChatModel first = factory.createChatModel(config);
		ChatModel second = factory.createChatModel(config);

		assertSame(first, second);
	}

	@Test
	void createChatModel_createsNewInstanceWhenConfigChanges() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());

		ChatModel first = factory.createChatModel(chatConfig("qwen-max"));
		ChatModel second = factory.createChatModel(chatConfig("qwen-plus"));

		assertNotSame(first, second);
	}

	@Test
	void createChatModel_createsNewInstanceWhenBaseUrlChanges() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO firstConfig = chatConfig("qwen-max");
		ModelConfigDTO secondConfig = chatConfig("qwen-max");
		secondConfig.setBaseUrl("https://dashscope-alt.example.com/compatible-mode");

		ChatModel first = factory.createChatModel(firstConfig);
		ChatModel second = factory.createChatModel(secondConfig);

		assertNotSame(first, second);
	}

	@Test
	void createChatModel_createsNewInstanceWhenCompletionsPathChanges() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO firstConfig = chatConfig("qwen-max");
		ModelConfigDTO secondConfig = chatConfig("qwen-max");
		secondConfig.setCompletionsPath("/v1/responses");

		ChatModel first = factory.createChatModel(firstConfig);
		ChatModel second = factory.createChatModel(secondConfig);

		assertNotSame(first, second);
	}

	@Test
	void createChatModel_createsNewInstanceWhenProxyChanges() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO firstConfig = chatConfig("qwen-max");
		ModelConfigDTO secondConfig = chatConfig("qwen-max");
		secondConfig.setProxyEnabled(true);
		secondConfig.setProxyHost("127.0.0.1");
		secondConfig.setProxyPort(10808);

		ChatModel first = factory.createChatModel(firstConfig);
		ChatModel second = factory.createChatModel(secondConfig);

		assertNotSame(first, second);
	}

	@Test
	void createChatModel_canDisableCache() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("spring.ai.agent.model-cache.enabled", "false");
		DynamicModelFactory factory = new DynamicModelFactory(environment);
		ModelConfigDTO config = chatConfig("qwen-max");

		ChatModel first = factory.createChatModel(config);
		ChatModel second = factory.createChatModel(config);

		assertNotSame(first, second);
	}

	@Test
	void createChatModel_logsCacheHitWithoutApiKey(CapturedOutput output) {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("qwen-max");

		factory.createChatModel(config);
		factory.createChatModel(config);

		assertTrue(output.getOut().contains("cacheHit=false"));
		assertTrue(output.getOut().contains("cacheHit=true"));
		assertFalse(output.getOut().contains("secret"));
	}

	@Test
	void createChatModel_rejectsMaxTokensOutsideSpringAiIntegerRange() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("qwen-max");
		config.setMaxTokens((long) Integer.MAX_VALUE + 1);

		assertThrows(IllegalArgumentException.class, () -> factory.createChatModel(config));
	}

	@Test
	void createChatModel_rejectsInvalidConfiguredModelConnectTimeout() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getRuntime().setModelHttpConnectTimeout(Duration.ZERO);
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment(), properties);

		assertThrows(IllegalStateException.class, () -> factory.createChatModel(chatConfig("qwen-max")));
	}

	@Test
	void createRouteModel_usesZeroTemperatureWithoutChangingChatTemperature() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("qwen-max");

		OpenAiChatModel routeModel = (OpenAiChatModel) factory.createRouteModel(config, Duration.ofSeconds(10),
				RouteModelProtocol.JSON_SCHEMA);
		OpenAiChatModel chatModel = (OpenAiChatModel) factory.createChatModel(config);
		OpenAiChatOptions routeOptions = (OpenAiChatOptions) routeModel.getDefaultOptions();
		OpenAiChatOptions chatOptions = (OpenAiChatOptions) chatModel.getDefaultOptions();

		assertEquals(0D, routeOptions.getTemperature().doubleValue());
		assertEquals(0.1D, chatOptions.getTemperature().doubleValue());
		assertEquals(ResponseFormat.Type.JSON_SCHEMA, routeOptions.getResponseFormat().getType());
		assertTrue(routeOptions.getResponseFormat().getJsonSchema().getStrict());
	}

	@Test
	void createRouteModelForProtocolUsesNegotiatedResponseFormatWithoutStreamParameters() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("qwen-plus");

		OpenAiChatOptions strict = routeOptions(factory, config, RouteModelOutputProtocol.STRICT_SCHEMA);
		OpenAiChatOptions jsonObject = routeOptions(factory, config, RouteModelOutputProtocol.JSON_OBJECT);
		OpenAiChatOptions promptJson = routeOptions(factory, config, RouteModelOutputProtocol.PROMPT_JSON);

		assertEquals(ResponseFormat.Type.JSON_SCHEMA, strict.getResponseFormat().getType());
		assertTrue(strict.getResponseFormat().getJsonSchema().getStrict());
		assertEquals(ResponseFormat.Type.JSON_OBJECT, jsonObject.getResponseFormat().getType());
		assertNull(promptJson.getResponseFormat());
		for (OpenAiChatOptions options : java.util.List.of(strict, jsonObject, promptJson)) {
			assertFalse(options.getStreamUsage());
			assertNull(options.getStreamOptions());
		}
	}

	@Test
	void functionCallingRouteModelUsesOnlyRouteSubmissionTool() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = nativeConfig("qwen-plus", ModelEndpointDialect.DASHSCOPE_NATIVE);

		OpenAiChatOptions options = routeOptions(factory, config, RouteModelOutputProtocol.FUNCTION_CALL);

		assertNull(options.getResponseFormat());
		assertEquals(1, options.getToolCallbacks().size());
		assertEquals("submit_route_plan", options.getToolCallbacks().get(0).getToolDefinition().name());
		assertEquals("submit_route_plan",
				new ObjectMapper().valueToTree(options.getToolChoice()).path("function").path("name").asText());
		assertEquals(Boolean.FALSE, options.getParallelToolCalls());
		assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
		assertThrows(IllegalStateException.class, () -> options.getToolCallbacks().get(0).call("{}"));
	}

	@Test
	void createRouteModelForProtocolInheritsConfiguredTemperaturePolicy() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("compatible-chat");
		config.setTemperaturePolicy(ModelTemperaturePolicy.OMIT.name());

		OpenAiChatOptions options = routeOptions(factory, config, RouteModelOutputProtocol.JSON_OBJECT);

		assertNull(options.getTemperature());
	}

	@Test
	void boundedStructuredModelWithSchemaUsesConfiguredStructuredOutputPreference() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("qwen-plus");
		config.setStructuredOutputMode(ModelStructuredOutputMode.STRICT_JSON_SCHEMA.name());

		OpenAiChatModel model = (OpenAiChatModel) factory.createBoundedStructuredModel(config,
				Duration.ofSeconds(10), 128L, RouteModelProtocol.JSON_SCHEMA);
		OpenAiChatOptions options = (OpenAiChatOptions) model.getDefaultOptions();

		assertEquals(ResponseFormat.Type.JSON_SCHEMA, options.getResponseFormat().getType());
		assertTrue(options.getResponseFormat().getJsonSchema().getStrict());
	}

	@Test
	void boundedJsonObjectModelUsesJsonObjectResponseFormatWithoutSchema() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("qwen-plus");
		config.setStructuredOutputMode(ModelStructuredOutputMode.JSON_OBJECT.name());

		OpenAiChatModel model = (OpenAiChatModel) factory.createBoundedStructuredModel(config,
				Duration.ofSeconds(10), 128L);
		OpenAiChatOptions options = (OpenAiChatOptions) model.getDefaultOptions();

		assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
	}

	@Test
	void boundedStrictModelWithoutSchemaFailsClosed() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("qwen-plus");
		config.setStructuredOutputMode(ModelStructuredOutputMode.STRICT_JSON_SCHEMA.name());

		assertThrows(IllegalArgumentException.class,
				() -> factory.createBoundedStructuredModel(config, Duration.ofSeconds(10), 128L));
	}

	@Test
	void boundedModelRejectsNonPositiveOutputBudget() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());

		assertThrows(IllegalArgumentException.class,
				() -> factory.createBoundedStructuredModel(chatConfig("qwen-plus"), Duration.ofSeconds(10), 0L));
	}

	@Test
	void deterministicPlannerKeepsItsOverridesWhileFlowUsesConfiguredOptions() throws Exception {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = nativeConfig("qwen-plus", ModelEndpointDialect.DASHSCOPE_NATIVE);
		config.setCapabilityProfile(ModelCapabilityProfile.QWEN_HYBRID.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningBudgetTokens(1024L);
		config.setTemperature(0.7D);
		config.setStructuredOutputMode(ModelStructuredOutputMode.JSON_OBJECT.name());

		DeterministicPlannerModel planner = factory.createDeterministicPlannerModel(config, Duration.ofSeconds(8), 2048L,
				"{\"type\":\"object\"}");
		OpenAiChatOptions plannerOptions = (OpenAiChatOptions) ((OpenAiChatModel) planner.model()).getDefaultOptions();
		FlowExtractionModel flow = factory.createFlowExtractionModel(config, Duration.ofSeconds(8), 800L,
				"{\"type\":\"object\",\"properties\":{\"set\":{\"type\":\"object\",\"additionalProperties\":false}},\"required\":[\"set\"],\"additionalProperties\":false}");
		OpenAiChatOptions flowOptions = (OpenAiChatOptions) ((OpenAiChatModel) flow.model()).getDefaultOptions();

		assertEquals(ModelStructuredOutputMode.JSON_OBJECT, planner.outputMode());
		assertEquals(2048, plannerOptions.getMaxTokens());
		assertEquals(0D, plannerOptions.getTemperature().doubleValue());
		assertEquals(Map.of("enable_thinking", false), plannerOptions.getExtraBody());
		assertEquals(ResponseFormat.Type.JSON_OBJECT, plannerOptions.getResponseFormat().getType());
		assertFalse(plannerOptions.getStreamUsage());
		assertEquals(ModelStructuredOutputMode.JSON_OBJECT, flow.outputMode());
		assertEquals(800, flowOptions.getMaxTokens());
		assertEquals(0.7D, flowOptions.getTemperature().doubleValue());
		assertEquals(Map.of("enable_thinking", true, "thinking_budget", 1024L), flowOptions.getExtraBody());
		assertEquals(ResponseFormat.Type.JSON_OBJECT, flowOptions.getResponseFormat().getType());
		assertFalse(flowOptions.getStreamUsage());
		assertSingleAttempt((OpenAiChatModel) planner.model());
		assertSingleAttempt((OpenAiChatModel) flow.model());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("flowSupportedDialectProfiles")
	void flowExtractionAutoAndPromptJsonUseNoResponseFormatForEverySupportedProfile(
			ModelCapabilityDescriptorResp descriptor) {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		for (String configuredMode : java.util.Arrays.asList(null, ModelStructuredOutputMode.AUTO.name(),
				ModelStructuredOutputMode.PROMPT_JSON.name())) {
			ModelConfigDTO config = flowConfig(descriptor);
			config.setStructuredOutputMode(configuredMode);

			FlowExtractionModel flow = factory.createFlowExtractionModel(config, Duration.ofSeconds(8), 1024L,
					"{\"type\":\"object\"}");
			OpenAiChatOptions options = flowOptions(flow);

			assertEquals(ModelStructuredOutputMode.PROMPT_JSON, flow.outputMode());
			assertNull(options.getResponseFormat());
			assertFlowOptionsMatchResolvedRequest(factory, config, options);
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("flowSupportedDialectProfiles")
	void flowExtractionExplicitProtocolsUseTheirConfiguredResponseFormat(ModelCapabilityDescriptorResp descriptor) {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		for (ModelStructuredOutputMode mode : java.util.List.of(ModelStructuredOutputMode.STRICT_JSON_SCHEMA,
				ModelStructuredOutputMode.JSON_OBJECT)) {
			ModelConfigDTO config = flowConfig(descriptor);
			config.setStructuredOutputMode(mode.name());

			FlowExtractionModel flow = factory.createFlowExtractionModel(config, Duration.ofSeconds(8), 1024L,
					"{\"type\":\"object\"}");
			OpenAiChatOptions options = flowOptions(flow);

			assertEquals(mode, flow.outputMode());
			assertEquals(mode == ModelStructuredOutputMode.STRICT_JSON_SCHEMA ? ResponseFormat.Type.JSON_SCHEMA
					: ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
			if (mode == ModelStructuredOutputMode.STRICT_JSON_SCHEMA) {
				assertTrue(options.getResponseFormat().getJsonSchema().getStrict());
			}
		}
	}

	@Test
	void stepFunAutoFlowUsesPromptJsonWithoutForcedReasoningFields() throws Exception {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = nativeConfig("step-3.7-flash", ModelEndpointDialect.STEPFUN_NATIVE);
		config.setCapabilityProfile(ModelCapabilityProfile.AUTO.name());
		config.setReasoningMode(ModelReasoningMode.AUTO.name());
		config.setStructuredOutputMode(ModelStructuredOutputMode.AUTO.name());
		config.setTemperature(0.7D);

		FlowExtractionModel flow = factory.createFlowExtractionModel(config, Duration.ofSeconds(8), 1024L,
				"{\"type\":\"object\"}");
		OpenAiChatModel model = (OpenAiChatModel) flow.model();
		OpenAiChatOptions options = (OpenAiChatOptions) model.getDefaultOptions();
		JsonNode request = new ObjectMapper().valueToTree(createRequest(model));

		assertEquals(ModelStructuredOutputMode.PROMPT_JSON, flow.outputMode());
		assertNull(options.getResponseFormat());
		assertEquals(0.7D, options.getTemperature().doubleValue());
		assertNull(options.getReasoningEffort());
		assertTrue(options.getExtraBody() == null || options.getExtraBody().isEmpty());
		assertFalse(request.has("response_format"));
		assertFalse(request.has("reasoning_effort"));
		assertSingleAttempt(model);
	}

	@Test
	void deterministicPlannerSelectsDeclaredStructuredProtocolFallback() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("qwen-plus");

		DeterministicPlannerModel strict = factory.createDeterministicPlannerModel(config, Duration.ofSeconds(8), 128L,
				ModelRequestCapabilities.builder()
					.structuredOutputModes(Set.of(ModelStructuredOutputMode.STRICT_JSON_SCHEMA)).build(),
				"{\"type\":\"object\"}");
		DeterministicPlannerModel jsonObject = factory.createDeterministicPlannerModel(config, Duration.ofSeconds(8), 128L,
				ModelRequestCapabilities.builder().structuredOutputModes(Set.of(ModelStructuredOutputMode.JSON_OBJECT)).build(),
				"{\"type\":\"object\"}");
		DeterministicPlannerModel promptJson = factory.createDeterministicPlannerModel(config, Duration.ofSeconds(8), 128L,
				ModelRequestCapabilities.builder().structuredOutputModes(Set.of()).build(),
				"{\"type\":\"object\"}");

		assertEquals(ModelStructuredOutputMode.STRICT_JSON_SCHEMA, strict.outputMode());
		assertEquals(ResponseFormat.Type.JSON_SCHEMA,
				((OpenAiChatOptions) ((OpenAiChatModel) strict.model()).getDefaultOptions()).getResponseFormat().getType());
		assertEquals(ModelStructuredOutputMode.JSON_OBJECT, jsonObject.outputMode());
		assertEquals(ResponseFormat.Type.JSON_OBJECT,
				((OpenAiChatOptions) ((OpenAiChatModel) jsonObject.model()).getDefaultOptions()).getResponseFormat().getType());
		assertTrue(promptJson.requiresPromptJson());
		assertNull(((OpenAiChatOptions) ((OpenAiChatModel) promptJson.model()).getDefaultOptions()).getResponseFormat());
	}

	@Test
	void deterministicPlannerWithoutCapabilitySnapshotUsesDeclaredProtocolDefaults() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO nativeConfig = nativeConfig("qwen-plus", ModelEndpointDialect.DASHSCOPE_NATIVE);
		ModelConfigDTO customConfig = nativeConfig("custom-chat", ModelEndpointDialect.CUSTOM);

		DeterministicPlannerModel strict = factory.createDeterministicPlannerModel(nativeConfig, Duration.ofSeconds(8),
				128L, "{\"type\":\"object\"}");
		DeterministicPlannerModel promptJson = factory.createDeterministicPlannerModel(customConfig,
				Duration.ofSeconds(8), 128L, "{\"type\":\"object\"}");

		assertEquals(ModelStructuredOutputMode.STRICT_JSON_SCHEMA, strict.outputMode());
		assertEquals(ResponseFormat.Type.JSON_SCHEMA,
				((OpenAiChatOptions) ((OpenAiChatModel) strict.model()).getDefaultOptions()).getResponseFormat().getType());
		assertTrue(promptJson.requiresPromptJson());
		assertNull(((OpenAiChatOptions) ((OpenAiChatModel) promptJson.model()).getDefaultOptions()).getResponseFormat());
	}

	@Test
	void deterministicPlannerRejectsModelThatCannotDisableReasoning() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = nativeConfig("step-1", ModelEndpointDialect.STEPFUN_NATIVE);
		config.setCapabilityProfile(ModelCapabilityProfile.STEPFUN_REASONING.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());

		assertThrows(DeterministicPlannerModelCapabilityException.class,
				() -> factory.createDeterministicPlannerModel(config, Duration.ofSeconds(8), 2048L,
						"{\"type\":\"object\"}"));
	}

	@Test
	void explicitRouteProtocolOverridesConfiguredOutputPreference() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("qwen-plus");
		config.setStructuredOutputMode(ModelStructuredOutputMode.PROMPT_JSON.name());

		OpenAiChatModel model = (OpenAiChatModel) factory.createRouteModelForProtocol(config, Duration.ofSeconds(10),
				Duration.ofSeconds(2), RouteModelOutputProtocol.STRICT_SCHEMA, ModelRequestOptionsOverride.none(), null,
				RouteModelProtocol.JSON_SCHEMA);
		OpenAiChatOptions options = (OpenAiChatOptions) model.getDefaultOptions();

		assertEquals(ResponseFormat.Type.JSON_SCHEMA, options.getResponseFormat().getType());
	}

	@Test
	void explicitRouteProtocolFailsClosedWhenProbedCapabilityDoesNotSupportIt() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelRequestCapabilities capabilities = ModelRequestCapabilities.builder()
			.structuredOutputModes(Set.of(ModelStructuredOutputMode.JSON_OBJECT)).build();

		assertThrows(IllegalArgumentException.class,
				() -> factory.createRouteModelForProtocol(chatConfig("qwen-plus"), Duration.ofSeconds(10),
						Duration.ofSeconds(2), RouteModelOutputProtocol.STRICT_SCHEMA, ModelRequestOptionsOverride.none(),
						capabilities, RouteModelProtocol.JSON_SCHEMA));
	}

	@Test
	void deepSeekReasoningFieldsAreFlattenedIntoProviderRequest() throws Exception {
		ModelConfigDTO config = nativeConfig("deepseek-reasoner", ModelEndpointDialect.DEEPSEEK_NATIVE);
		config.setCapabilityProfile(ModelCapabilityProfile.DEEPSEEK_THINKING.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());
		OpenAiChatModel model = (OpenAiChatModel) new DynamicModelFactory(new MockEnvironment())
			.createRouteModelForProtocol(config, Duration.ofSeconds(10), Duration.ofSeconds(2),
					RouteModelOutputProtocol.JSON_OBJECT, ModelRequestOptionsOverride.none(), null,
					RouteModelProtocol.JSON_SCHEMA);

		OpenAiApi.ChatCompletionRequest request = createRequest(model);
		injectProviderFields(model, request);
		JsonNode json = new ObjectMapper().valueToTree(request);

		assertEquals("enabled", json.path("thinking").path("type").asText(), json.toString());
		assertEquals("high", json.path("reasoning_effort").asText(), json.toString());
		assertFalse(json.has("extra_body"));
	}

	@Test
	void qwenRouteOptionsComeFromDashScopeDialectConfiguration() {
		ModelConfigDTO config = nativeConfig("qwen-plus", ModelEndpointDialect.DASHSCOPE_NATIVE);
		config.setReasoningMode(ModelReasoningMode.DISABLED.name());

		OpenAiChatOptions options = routeOptions(new DynamicModelFactory(new MockEnvironment()), config,
				RouteModelOutputProtocol.JSON_OBJECT);

		assertEquals(Map.of("enable_thinking", false), options.getExtraBody());
		assertEquals(0D, options.getTemperature().doubleValue());
	}

	@Test
	void stepFunRouteOptionsUseConfiguredReasoningEffort() {
		ModelConfigDTO config = nativeConfig("step-1", ModelEndpointDialect.STEPFUN_NATIVE);
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());

		OpenAiChatOptions options = routeOptions(new DynamicModelFactory(new MockEnvironment()), config,
				RouteModelOutputProtocol.JSON_OBJECT);

		assertEquals("high", options.getReasoningEffort());
		assertEquals(0D, options.getTemperature().doubleValue());
	}

	@Test
	void deepSeekRouteOptionsUseThinkingObjectAndOmitTemperature() {
		ModelConfigDTO config = nativeConfig("deepseek-reasoner", ModelEndpointDialect.DEEPSEEK_NATIVE);
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());

		OpenAiChatOptions options = routeOptions(new DynamicModelFactory(new MockEnvironment()), config,
				RouteModelOutputProtocol.JSON_OBJECT);

		assertEquals(Map.of("thinking", Map.of("type", "enabled")), options.getExtraBody());
		assertNull(options.getTemperature());
	}

	@Test
	void glmRouteOptionsUseThinkingObjectAndKeepSupportedTemperature() {
		ModelConfigDTO config = nativeConfig("glm-4.5", ModelEndpointDialect.ZHIPU_NATIVE);
		config.setReasoningMode(ModelReasoningMode.DISABLED.name());

		OpenAiChatOptions options = routeOptions(new DynamicModelFactory(new MockEnvironment()), config,
				RouteModelOutputProtocol.JSON_OBJECT);

		assertEquals(Map.of("thinking", Map.of("type", "disabled")), options.getExtraBody());
		assertEquals(0D, options.getTemperature().doubleValue());
	}

	@Test
	void kimiRouteOptionsUseThinkingObjectAndOmitTemperature() {
		ModelConfigDTO config = nativeConfig("kimi-k2", ModelEndpointDialect.MOONSHOT_NATIVE);
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());

		OpenAiChatOptions options = routeOptions(new DynamicModelFactory(new MockEnvironment()), config,
				RouteModelOutputProtocol.JSON_OBJECT);

		assertEquals(Map.of("thinking", Map.of("type", "enabled")), options.getExtraBody());
		assertNull(options.getTemperature());
	}

	@Test
	void kimiExplicitProfilesProduceTheirOwnWireContracts() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());

		ModelConfigDTO k3 = nativeConfig("private-k3-alias", ModelEndpointDialect.MOONSHOT_NATIVE);
		k3.setCapabilityProfile(ModelCapabilityProfile.KIMI_K3_REASONING.name());
		k3.setReasoningMode(ModelReasoningMode.ENABLED.name());
		k3.setReasoningLevel(ModelReasoningLevel.HIGH.name());

		ModelConfigDTO k26 = nativeConfig("private-k26-alias", ModelEndpointDialect.MOONSHOT_NATIVE);
		k26.setCapabilityProfile(ModelCapabilityProfile.KIMI_K26_THINKING.name());
		k26.setReasoningMode(ModelReasoningMode.DISABLED.name());

		ModelConfigDTO k27 = nativeConfig("private-k27-alias", ModelEndpointDialect.MOONSHOT_NATIVE);
		k27.setCapabilityProfile(ModelCapabilityProfile.KIMI_K27_CODE.name());
		k27.setReasoningMode(ModelReasoningMode.ENABLED.name());

		OpenAiChatOptions k3Options = routeOptions(factory, k3, RouteModelOutputProtocol.JSON_OBJECT);
		OpenAiChatOptions k26Options = routeOptions(factory, k26, RouteModelOutputProtocol.JSON_OBJECT);
		OpenAiChatOptions k27Options = routeOptions(factory, k27, RouteModelOutputProtocol.JSON_OBJECT);

		assertEquals("high", k3Options.getReasoningEffort());
		assertNull(k3Options.getExtraBody());
		assertEquals(Map.of("thinking", Map.of("type", "disabled")), k26Options.getExtraBody());
		assertEquals(Map.of("thinking", Map.of("type", "enabled")), k27Options.getExtraBody());
	}

	@Test
	void createRouteModel_appliesProviderSafeTokenFieldAndReasoningEffort() {
		DynamicModelFactory factory = new DynamicModelFactory(new MockEnvironment());
		ModelConfigDTO config = chatConfig("step-1");
		config.setEndpointDialect("STEPFUN_NATIVE");
		OpenAiChatModel model = (OpenAiChatModel) factory.createRouteModel(config, Duration.ofSeconds(10),
				Duration.ofSeconds(2), ModelRequestOptionsOverride.builder()
					.maxOutputTokens(128L)
					.tokenLimitMode(ModelTokenLimitMode.MAX_COMPLETION_TOKENS)
					.reasoningLevel(com.sn68.agent.dataagent.enums.ModelReasoningLevel.LOW)
					.temperaturePolicy(ModelTemperaturePolicy.OMIT)
					.structuredOutputMode(com.sn68.agent.dataagent.enums.ModelStructuredOutputMode.JSON_OBJECT)
					.build(), null, RouteModelProtocol.JSON_SCHEMA);
		OpenAiChatOptions options = (OpenAiChatOptions) model.getDefaultOptions();

		assertNull(options.getMaxCompletionTokens());
		assertEquals(128, options.getMaxTokens());
		assertEquals("low", options.getReasoningEffort());
		assertNull(options.getTemperature());
		assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
	}

	private static Stream<ModelCapabilityDescriptorResp> flowSupportedDialectProfiles() {
		return new ModelRequestOptionsResolver().capabilityDescriptors().stream();
	}

	private ModelConfigDTO flowConfig(ModelCapabilityDescriptorResp descriptor) {
		ModelConfigDTO config = nativeConfig("flow-" + descriptor.endpointDialect().name().toLowerCase(),
				descriptor.endpointDialect());
		config.setCapabilityProfile(descriptor.capabilityProfile().name());
		return config;
	}

	private OpenAiChatOptions flowOptions(FlowExtractionModel flow) {
		return (OpenAiChatOptions) ((OpenAiChatModel) flow.model()).getDefaultOptions();
	}

	private void assertFlowOptionsMatchResolvedRequest(DynamicModelFactory factory, ModelConfigDTO config,
			OpenAiChatOptions options) {
		ResolvedModelRequestOptions resolved = factory.resolveRequestOptions(config,
				ModelRequestOptionsOverride.builder()
					.maxOutputTokens(1024L)
					.structuredOutputMode(ModelStructuredOutputMode.PROMPT_JSON)
					.build(), null);

		assertEquals(resolved.temperature(), options.getTemperature());
		assertEquals(resolved.reasoningEffort(), options.getReasoningEffort());
		assertEquals(resolved.extraBody(), options.getExtraBody() == null ? Map.of() : options.getExtraBody());
		if (resolved.tokenLimitMode() == ModelTokenLimitMode.MAX_COMPLETION_TOKENS) {
			assertEquals(resolved.maxOutputTokens(), options.getMaxCompletionTokens());
			return;
		}
		assertEquals(resolved.maxOutputTokens(), options.getMaxTokens());
	}

	private OpenAiChatOptions routeOptions(DynamicModelFactory factory, ModelConfigDTO config,
			RouteModelOutputProtocol protocol) {
		OpenAiChatModel model = (OpenAiChatModel) factory.createRouteModelForProtocol(config, Duration.ofSeconds(10),
				Duration.ofSeconds(2), protocol, ModelRequestOptionsOverride.builder()
					.maxOutputTokens(256L)
					.temperature(0D)
					.build(), null, RouteModelProtocol.JSON_SCHEMA);
		return (OpenAiChatOptions) model.getDefaultOptions();
	}

	private OpenAiApi.ChatCompletionRequest createRequest(OpenAiChatModel model) throws Exception {
		Method buildRequestPrompt = OpenAiChatModel.class.getDeclaredMethod("buildRequestPrompt", Prompt.class);
		buildRequestPrompt.setAccessible(true);
		Prompt requestPrompt = (Prompt) buildRequestPrompt.invoke(model, new Prompt("route probe"));
		Method createRequest = OpenAiChatModel.class.getDeclaredMethod("createRequest", Prompt.class, boolean.class);
		createRequest.setAccessible(true);
		return (OpenAiApi.ChatCompletionRequest) createRequest.invoke(model, requestPrompt, false);
	}

	private void injectProviderFields(OpenAiChatModel model, OpenAiApi.ChatCompletionRequest request) throws Exception {
		Field apiField = OpenAiChatModel.class.getDeclaredField("openAiApi");
		apiField.setAccessible(true);
		Object api = apiField.get(model);
		Method mergeProviderFields = api.getClass().getDeclaredMethod("mergeProviderFields",
				OpenAiApi.ChatCompletionRequest.class);
		mergeProviderFields.setAccessible(true);
		mergeProviderFields.invoke(api, request);
	}

	private void assertSingleAttempt(OpenAiChatModel model) throws Exception {
		Field retryTemplateField = OpenAiChatModel.class.getDeclaredField("retryTemplate");
		retryTemplateField.setAccessible(true);
		RetryTemplate retryTemplate = (RetryTemplate) retryTemplateField.get(model);
		AtomicInteger attempts = new AtomicInteger();

		assertThrows(RuntimeException.class,
				() -> retryTemplate.execute(context -> {
					attempts.incrementAndGet();
					throw new IllegalStateException("expected");
				}));
		assertEquals(1, attempts.get());
	}

	private ModelConfigDTO nativeConfig(String modelName, ModelEndpointDialect dialect) {
		ModelConfigDTO config = chatConfig(modelName);
		config.setEndpointDialect(dialect.name());
		return config;
	}

	private ModelConfigDTO chatConfig(String modelName) {
		return ModelConfigDTO.builder()
			.id(1L)
			.provider("qwen")
			.apiKey("secret")
			.baseUrl("https://dashscope.example.com/compatible-mode")
			.modelName(modelName)
			.modelType("CHAT")
			.temperature(0.1)
			.maxTokens(2000L)
			.completionsPath("/v1/chat/completions")
			.proxyEnabled(false)
			.build();
	}

}
