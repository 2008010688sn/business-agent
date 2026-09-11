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
package com.sn68.agent.dataagent.service.aimodelconfig.options;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.ModelCapabilityDescriptorResp;
import com.sn68.agent.dataagent.enums.ModelCapabilityProfile;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelPreservedReasoningPolicy;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelTemperaturePolicy;
import com.sn68.agent.dataagent.enums.ModelTokenAccounting;
import com.sn68.agent.dataagent.enums.ModelTokenLimitMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRequestOptionsResolverTest {

	private final ModelRequestOptionsResolver resolver = new ModelRequestOptionsResolver();

	@ParameterizedTest(name = "{0}")
	@MethodSource("nativeDialectDefaults")
	void nativeDialectDefaultsAndCapabilitiesAreProviderSafe(ModelEndpointDialect dialect,
			ModelReasoningProtocol protocol, boolean budgetSupported, ModelTemperaturePolicy temperaturePolicy,
			boolean temperatureSupported) {
		ResolvedModelRequestOptions resolved = resolver.resolve(config(dialect.name()));
		ModelRequestCapabilities capabilities = resolver.adapter(dialect).capabilities();

		assertEquals(protocol, resolved.reasoningProtocol());
		assertEquals(ModelReasoningMode.AUTO, resolved.reasoningMode());
		assertNull(resolved.reasoningLevel());
		assertNull(resolved.reasoningBudgetTokens());
		assertEquals(ModelTokenLimitMode.MAX_TOKENS, resolved.tokenLimitMode());
		assertEquals(2000, resolved.maxOutputTokens());
		assertEquals(temperaturePolicy, resolved.temperaturePolicy());
		assertEquals(temperatureSupported ? 0.2D : null, resolved.temperature());
		assertEquals(ModelPreservedReasoningPolicy.DROP, resolved.preservedReasoningPolicy());
		assertEquals(ModelStructuredOutputMode.JSON_OBJECT, resolved.structuredOutputMode());
		assertEquals(Map.of(), resolved.extraBody());
		assertNull(resolved.reasoningEffort());

		assertEquals(Set.of(protocol), capabilities.reasoningProtocols());
		assertEquals(EnumSet.allOf(ModelReasoningMode.class), capabilities.reasoningModes());
		assertEquals(protocol == ModelReasoningProtocol.REASONING_EFFORT
				? Set.of(ModelReasoningLevel.NONE, ModelReasoningLevel.LOW, ModelReasoningLevel.MEDIUM,
						ModelReasoningLevel.HIGH)
				: Set.of(ModelReasoningLevel.NONE), capabilities.reasoningLevels());
		assertEquals(budgetSupported, capabilities.reasoningBudgetSupported());
		assertEquals(Set.of(ModelTokenLimitMode.MAX_TOKENS), capabilities.tokenLimitModes());
		assertEquals(temperatureSupported, capabilities.temperatureSupported());
		assertEquals(Set.of(ModelStructuredOutputMode.STRICT_JSON_SCHEMA, ModelStructuredOutputMode.JSON_OBJECT,
				ModelStructuredOutputMode.PROMPT_JSON),
				capabilities.structuredOutputModes());
		assertEquals(Set.of(ModelPreservedReasoningPolicy.DROP), capabilities.preservedReasoningPolicies());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("nativeReasoningWireShapes")
	void nativeDialectRendersProviderReasoningWireShape(ModelEndpointDialect dialect, ModelReasoningLevel level,
			Long budget, Map<String, Object> extraBody, String reasoningEffort) {
		ModelConfigDTO config = config(dialect.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(level == null ? null : level.name());
		config.setReasoningBudgetTokens(budget);

		ResolvedModelRequestOptions resolved = resolver.resolve(config);

		assertEquals(ModelReasoningMode.ENABLED, resolved.reasoningMode());
		assertEquals(level, resolved.reasoningLevel());
		assertEquals(budget, resolved.reasoningBudgetTokens());
		assertEquals(extraBody, resolved.extraBody());
		assertEquals(reasoningEffort, resolved.reasoningEffort());
	}

	@ParameterizedTest(name = "{0} rejects {1}")
	@MethodSource("foreignNativeProtocols")
	void nativeDialectRejectsForeignReasoningProtocol(ModelEndpointDialect dialect,
			ModelReasoningProtocol foreignProtocol) {
		ModelConfigDTO config = config(dialect.name());
		config.setReasoningProtocol(foreignProtocol.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(config));

		assertEquals("reasoningProtocol " + foreignProtocol + " is not supported by " + dialect,
				error.getMessage());
	}

	@Test
	void stepFunRejectsReasoningBudget() {
		ModelConfigDTO config = config(ModelEndpointDialect.STEPFUN_NATIVE.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.MEDIUM.name());
		config.setReasoningBudgetTokens(512L);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(config));

		assertEquals("reasoningBudgetTokens is not supported by STEPFUN_NATIVE", error.getMessage());
	}

	@Test
	void deepSeekRejectsUndocumentedReasoningBudget() {
		ModelConfigDTO config = config(ModelEndpointDialect.DEEPSEEK_NATIVE.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningBudgetTokens(512L);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(config));

		assertEquals("reasoningBudgetTokens is not supported by DEEPSEEK_NATIVE", error.getMessage());
	}

	@Test
	void modelTokenAccountingIsSeparateFromWireDialect() {
		assertEquals(ModelTokenAccounting.MAX_TOKENS_INCLUDES_REASONING,
				resolver.resolve(config(ModelEndpointDialect.DASHSCOPE_NATIVE.name())).tokenAccounting());
		assertEquals(ModelTokenAccounting.UNKNOWN,
				resolver.resolve(config(ModelEndpointDialect.DEEPSEEK_NATIVE.name())).tokenAccounting());
		assertEquals(ModelTokenAccounting.UNKNOWN,
				resolver.resolve(config(ModelEndpointDialect.STEPFUN_NATIVE.name())).tokenAccounting());
		assertEquals(ModelTokenAccounting.UNKNOWN,
				resolver.resolve(config(ModelEndpointDialect.MOONSHOT_NATIVE.name())).tokenAccounting());
	}

	@Test
	void deepSeekProfileExplicitlyCombinesThinkingToggleAndEffortWithoutModelNameInference() {
		ModelConfigDTO config = config(ModelEndpointDialect.DEEPSEEK_NATIVE.name());
		config.setModelName("arbitrary-deployment-name");
		config.setCapabilityProfile(ModelCapabilityProfile.DEEPSEEK_THINKING.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());

		ResolvedModelRequestOptions resolved = resolver.resolve(config);

		assertEquals(ModelReasoningProtocol.THINKING_OBJECT_WITH_EFFORT, resolved.reasoningProtocol());
		assertEquals(Map.of("thinking", Map.of("type", "enabled")), resolved.extraBody());
		assertEquals("high", resolved.reasoningEffort());
		assertEquals(ModelTokenAccounting.UNKNOWN, resolved.tokenAccounting());
	}

	@Test
	void qwenThinkingOnlyProfileRejectsDisableWhileHybridProfileCanDisable() {
		ModelConfigDTO thinkingOnly = config(ModelEndpointDialect.DASHSCOPE_NATIVE.name());
		thinkingOnly.setCapabilityProfile(ModelCapabilityProfile.QWEN_THINKING_ONLY.name());
		thinkingOnly.setReasoningMode(ModelReasoningMode.DISABLED.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(thinkingOnly));
		assertEquals("reasoning disable is not supported by DASHSCOPE_NATIVE", error.getMessage());

		ModelConfigDTO hybrid = config(ModelEndpointDialect.DASHSCOPE_NATIVE.name());
		hybrid.setCapabilityProfile(ModelCapabilityProfile.QWEN_HYBRID.name());
		hybrid.setReasoningMode(ModelReasoningMode.DISABLED.name());
		assertEquals(Map.of("enable_thinking", false), resolver.resolve(hybrid).extraBody());
	}

	@Test
	void kimiProfilesRenderDistinctExplicitWireShapes() {
		ModelConfigDTO k3 = config(ModelEndpointDialect.MOONSHOT_NATIVE.name());
		k3.setCapabilityProfile(ModelCapabilityProfile.KIMI_K3_REASONING.name());
		k3.setReasoningMode(ModelReasoningMode.ENABLED.name());
		k3.setReasoningLevel(ModelReasoningLevel.HIGH.name());

		ModelConfigDTO k26 = config(ModelEndpointDialect.MOONSHOT_NATIVE.name());
		k26.setCapabilityProfile(ModelCapabilityProfile.KIMI_K26_THINKING.name());
		k26.setReasoningMode(ModelReasoningMode.DISABLED.name());

		ModelConfigDTO k27 = config(ModelEndpointDialect.MOONSHOT_NATIVE.name());
		k27.setCapabilityProfile(ModelCapabilityProfile.KIMI_K27_CODE.name());
		k27.setReasoningMode(ModelReasoningMode.ENABLED.name());

		ResolvedModelRequestOptions k3Resolved = resolver.resolve(k3);
		ResolvedModelRequestOptions k26Resolved = resolver.resolve(k26);
		ResolvedModelRequestOptions k27Resolved = resolver.resolve(k27);

		assertEquals("high", k3Resolved.reasoningEffort());
		assertEquals(Map.of(), k3Resolved.extraBody());
		assertEquals(Map.of("thinking", Map.of("type", "disabled")), k26Resolved.extraBody());
		assertEquals(Map.of("thinking", Map.of("type", "enabled")), k27Resolved.extraBody());
	}

	@Test
	void kimiK3ProfileSupportsLowHighMaxButRejectsMedium() {
		ModelConfigDTO max = config(ModelEndpointDialect.MOONSHOT_NATIVE.name());
		max.setCapabilityProfile(ModelCapabilityProfile.KIMI_K3_REASONING.name());
		max.setReasoningMode(ModelReasoningMode.ENABLED.name());
		max.setReasoningLevel(ModelReasoningLevel.MAX.name());
		assertEquals("max", resolver.resolve(max).reasoningEffort());

		ModelConfigDTO medium = config(ModelEndpointDialect.MOONSHOT_NATIVE.name());
		medium.setCapabilityProfile(ModelCapabilityProfile.KIMI_K3_REASONING.name());
		medium.setReasoningMode(ModelReasoningMode.ENABLED.name());
		medium.setReasoningLevel(ModelReasoningLevel.MEDIUM.name());
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(medium));
		assertEquals("reasoningLevel MEDIUM is not supported by MOONSHOT_NATIVE", error.getMessage());
	}

	@Test
	void kimiK27CodeProfileRejectsDisabledThinking() {
		ModelConfigDTO config = config(ModelEndpointDialect.MOONSHOT_NATIVE.name());
		config.setCapabilityProfile(ModelCapabilityProfile.KIMI_K27_CODE.name());

		ResolvedModelRequestOptions defaultOptions = resolver.resolve(config);
		assertEquals(ModelReasoningMode.ENABLED, defaultOptions.reasoningMode());
		assertEquals(Map.of("thinking", Map.of("type", "enabled")), defaultOptions.extraBody());

		config.setReasoningMode(ModelReasoningMode.DISABLED.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(config));

		assertEquals("reasoning disable is not supported by MOONSHOT_NATIVE", error.getMessage());
	}

	@Test
	void capabilityProfileRejectsMismatchedEndpointDialect() {
		ModelConfigDTO config = config(ModelEndpointDialect.DASHSCOPE_NATIVE.name());
		config.setCapabilityProfile(ModelCapabilityProfile.KIMI_K3_REASONING.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(config));

		assertEquals("capabilityProfile KIMI_K3_REASONING requires endpointDialect MOONSHOT_NATIVE",
				error.getMessage());
	}

	@Test
	void stepFunEnabledReasoningRequiresLevel() {
		ModelConfigDTO config = config(ModelEndpointDialect.STEPFUN_NATIVE.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(config));

		assertEquals("reasoningLevel is required when STEPFUN_NATIVE reasoning is enabled", error.getMessage());
	}

	@Test
	void stepFunRejectsUnsupportedEffortLevel() {
		ModelConfigDTO config = config(ModelEndpointDialect.STEPFUN_NATIVE.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.XHIGH.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(config));

		assertEquals("reasoningLevel XHIGH is not supported by STEPFUN_NATIVE", error.getMessage());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("booleanReasoningDialects")
	void booleanReasoningDialectsRejectUnsupportedEffortLevel(ModelEndpointDialect dialect) {
		ModelConfigDTO config = config(dialect.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(config));

		assertEquals("reasoningLevel HIGH is not supported by " + dialect, error.getMessage());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("temperatureOmittingDialects")
	void unsafeTemperatureOverrideCannotReenableTemperature(ModelEndpointDialect dialect) {
		ModelConfigDTO config = config(dialect.name());
		ModelRequestOptionsOverride override = ModelRequestOptionsOverride.builder()
			.temperature(0D)
			.temperaturePolicy(ModelTemperaturePolicy.SEND)
			.build();

		ResolvedModelRequestOptions resolved = resolver.resolve(config, override, null);

		assertEquals(ModelTemperaturePolicy.OMIT, resolved.temperaturePolicy());
		assertNull(resolved.temperature());
	}

	@Test
	void taskOverrideWinsAndUnsupportedFieldsAreOmitted() {
		ModelConfigDTO config = config("DASHSCOPE_NATIVE");
		config.setTemperature(0.4D);
		config.setMaxTokens(1000L);
		ModelRequestOptionsOverride override = ModelRequestOptionsOverride.builder()
			.reasoningMode(ModelReasoningMode.DISABLED)
			.maxOutputTokens(64L)
			.tokenLimitMode(ModelTokenLimitMode.MAX_COMPLETION_TOKENS)
			.temperature(0D)
			.temperaturePolicy(ModelTemperaturePolicy.SEND)
			.structuredOutputMode(ModelStructuredOutputMode.JSON_OBJECT)
			.build();
		ModelRequestCapabilities capabilities = ModelRequestCapabilities.builder()
			.reasoningProtocols(EnumSet.of(ModelReasoningProtocol.ENABLE_THINKING,
					ModelReasoningProtocol.NONE))
			.reasoningModes(EnumSet.of(ModelReasoningMode.AUTO, ModelReasoningMode.DISABLED))
			.reasoningLevels(EnumSet.of(ModelReasoningLevel.NONE))
			.reasoningBudgetSupported(false)
			.tokenLimitModes(EnumSet.of(ModelTokenLimitMode.MAX_COMPLETION_TOKENS))
			.temperatureSupported(false)
			.structuredOutputModes(EnumSet.of(ModelStructuredOutputMode.JSON_OBJECT,
					ModelStructuredOutputMode.PROMPT_JSON))
			.build();

		ResolvedModelRequestOptions resolved = resolver.resolve(config, override, capabilities);

		assertEquals(ModelReasoningProtocol.ENABLE_THINKING, resolved.reasoningProtocol());
		assertEquals(ModelReasoningMode.DISABLED, resolved.reasoningMode());
		assertNull(resolved.tokenLimitMode());
		assertNull(resolved.maxOutputTokens());
		assertNull(resolved.temperature());
		assertEquals(ModelTemperaturePolicy.OMIT, resolved.temperaturePolicy());
		assertEquals(ModelStructuredOutputMode.JSON_OBJECT, resolved.structuredOutputMode());
		assertEquals(Map.of("enable_thinking", false), resolved.extraBody());
	}

	@Test
	void dialectAdaptersRenderDistinctReasoningWireShapes() {
		ModelConfigDTO dashscope = config("DASHSCOPE_NATIVE");
		dashscope.setReasoningMode("ENABLED");
		dashscope.setReasoningBudgetTokens(2048L);
		ResolvedModelRequestOptions dashscopeOptions = resolver.resolve(dashscope);
		assertEquals(true, dashscopeOptions.extraBody().get("enable_thinking"));
		assertEquals(2048L, dashscopeOptions.extraBody().get("thinking_budget"));

		ModelConfigDTO deepseek = config("DEEPSEEK_NATIVE");
		deepseek.setReasoningMode("ENABLED");
		ResolvedModelRequestOptions deepseekOptions = resolver.resolve(deepseek);
		assertEquals(Map.of("type", "enabled"), deepseekOptions.extraBody().get("thinking"));

		ModelConfigDTO step = config("STEPFUN_NATIVE");
		step.setReasoningLevel("HIGH");
		ResolvedModelRequestOptions stepOptions = resolver.resolve(step);
		assertEquals("high", stepOptions.reasoningEffort());
		assertEquals(Map.of(), stepOptions.extraBody());
	}

	@Test
	void deepSeekDefaultThinkingDoesNotImplicitlySendReasoningEffort() {
		ModelConfigDTO config = config(ModelEndpointDialect.DEEPSEEK_NATIVE.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());

		ResolvedModelRequestOptions resolved = resolver.resolve(config);

		assertEquals(Map.of("type", "enabled"), resolved.extraBody().get("thinking"));
		assertNull(resolved.reasoningEffort());
	}

	@Test
	void deepSeekDefaultRejectsEffortLevelUntilModelProfileIsExplicit() {
		ModelConfigDTO config = config(ModelEndpointDialect.DEEPSEEK_NATIVE.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(config));

		assertEquals("reasoningLevel HIGH is not supported by DEEPSEEK_NATIVE", error.getMessage());
	}

	@Test
	void nativeReasoningDisableUsesExplicitProviderWireField() {
		ModelConfigDTO config = config(ModelEndpointDialect.DEEPSEEK_NATIVE.name());
		config.setReasoningMode(ModelReasoningMode.DISABLED.name());

		ResolvedModelRequestOptions resolved = resolver.resolve(config);

		assertEquals(Map.of("type", "disabled"), resolved.extraBody().get("thinking"));
	}

	@Test
	void nativeDialectDoesNotTreatNoneProtocolAsDisableAlias() {
		ModelConfigDTO config = config(ModelEndpointDialect.DEEPSEEK_NATIVE.name());
		config.setReasoningProtocol(ModelReasoningProtocol.NONE.name());

		assertThrows(IllegalArgumentException.class, () -> resolver.resolve(config));
	}

	@Test
	void deepSeekRejectsUnsupportedMediumEffortLevel() {
		ModelConfigDTO config = config(ModelEndpointDialect.DEEPSEEK_NATIVE.name());
		config.setCapabilityProfile(ModelCapabilityProfile.DEEPSEEK_THINKING.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.MEDIUM.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(config));

		assertEquals("reasoningLevel MEDIUM is not supported by DEEPSEEK_NATIVE", error.getMessage());
	}

	@Test
	void noReasoningProfilePreservesOpenAiCompatibleDefaults() {
		ModelConfigDTO config = config(ModelEndpointDialect.OPENAI_COMPATIBLE.name());
		config.setCapabilityProfile(ModelCapabilityProfile.NO_REASONING.name());

		ResolvedModelRequestOptions resolved = resolver.resolve(config);

		assertEquals(ModelReasoningProtocol.NONE, resolved.reasoningProtocol());
		assertEquals(ModelReasoningMode.DISABLED, resolved.reasoningMode());
		assertEquals(ModelTokenAccounting.OUTPUT_ONLY, resolved.tokenAccounting());
		assertEquals(ModelTokenLimitMode.MAX_TOKENS, resolved.tokenLimitMode());
		assertEquals(ModelTemperaturePolicy.SEND, resolved.temperaturePolicy());
		assertEquals(ModelStructuredOutputMode.STRICT_JSON_SCHEMA, resolved.structuredOutputMode());
	}

	@Test
	void noReasoningProfilePreservesCustomStructuredOutputDefault() {
		ModelConfigDTO config = config(ModelEndpointDialect.CUSTOM.name());
		config.setCapabilityProfile(ModelCapabilityProfile.NO_REASONING.name());

		ResolvedModelRequestOptions resolved = resolver.resolve(config);

		assertEquals(ModelReasoningProtocol.NONE, resolved.reasoningProtocol());
		assertEquals(ModelStructuredOutputMode.PROMPT_JSON, resolved.structuredOutputMode());
	}

	@Test
	void stepFunDoesNotFakeAReasoningEffortDisableValue() {
		ModelConfigDTO config = config(ModelEndpointDialect.STEPFUN_NATIVE.name());
		config.setReasoningMode(ModelReasoningMode.DISABLED.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(config));

		assertEquals("reasoning disable is not supported by STEPFUN_NATIVE", error.getMessage());
	}

	@Test
	void explicitStructuredOutputOverrideFailsWhenProbeDoesNotSupportIt() {
		ModelConfigDTO config = config(ModelEndpointDialect.OPENAI_COMPATIBLE.name());
		ModelRequestOptionsOverride override = ModelRequestOptionsOverride.builder()
			.structuredOutputMode(ModelStructuredOutputMode.STRICT_JSON_SCHEMA)
			.build();
		ModelRequestCapabilities capabilities = ModelRequestCapabilities.builder()
			.reasoningProtocols(EnumSet.of(ModelReasoningProtocol.NONE))
			.reasoningModes(EnumSet.allOf(ModelReasoningMode.class))
			.reasoningLevels(EnumSet.allOf(ModelReasoningLevel.class))
			.reasoningBudgetSupported(true)
			.reasoningEffortSupported(true)
			.reasoningDisableSupported(true)
			.tokenLimitModes(EnumSet.allOf(ModelTokenLimitMode.class))
			.temperatureSupported(true)
			.structuredOutputModes(EnumSet.of(ModelStructuredOutputMode.JSON_OBJECT))
			.preservedReasoningPolicies(EnumSet.of(ModelPreservedReasoningPolicy.DROP))
			.build();

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(config, override, capabilities));

		assertEquals("structuredOutputMode STRICT_JSON_SCHEMA is not supported by OPENAI_COMPATIBLE",
				error.getMessage());
	}

	@Test
	void tokenLimitAndTemperaturePolicyAreResolvedWithoutNullSentinels() {
		ModelConfigDTO config = config("OPENAI_COMPATIBLE");
		config.setTokenLimitMode("MAX_COMPLETION_TOKENS");
		config.setTemperaturePolicy("OMIT");
		ResolvedModelRequestOptions resolved = resolver.resolve(config);

		assertEquals(ModelTokenLimitMode.MAX_COMPLETION_TOKENS, resolved.tokenLimitMode());
		assertEquals(2000, resolved.maxOutputTokens());
		assertNull(resolved.temperature());
	}

	@Test
	void validatePersistableRejectsReasoningModeChangedByResolver() {
		ModelConfigDTO config = config(ModelEndpointDialect.OPENAI_COMPATIBLE.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.validatePersistable(config));

		assertEquals("reasoningMode 的持久化值 ENABLED 与实际生效值 DISABLED 不一致", error.getMessage());
	}

	@Test
	void validatePersistableRejectsReasoningLevelChangedByResolver() {
		ModelConfigDTO config = config(ModelEndpointDialect.OPENAI_COMPATIBLE.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.validatePersistable(config));

		assertEquals("reasoningLevel 的持久化值 HIGH 与实际生效值 NONE 不一致", error.getMessage());
	}

	@Test
	void validatePersistableRejectsReasoningBudgetChangedByResolver() {
		ModelConfigDTO config = config(ModelEndpointDialect.OPENAI_COMPATIBLE.name());
		config.setReasoningBudgetTokens(512L);

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.validatePersistable(config));

		assertEquals("reasoningBudgetTokens 的持久化值 512 与实际生效值 null 不一致", error.getMessage());
	}

	@Test
	void validatePersistableRejectsTokenLimitModeChangedByResolver() {
		ModelConfigDTO config = config(ModelEndpointDialect.STEPFUN_NATIVE.name());
		config.setTokenLimitMode(ModelTokenLimitMode.MAX_COMPLETION_TOKENS.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.validatePersistable(config));

		assertEquals("tokenLimitMode 的持久化值 MAX_COMPLETION_TOKENS 与实际生效值 MAX_TOKENS 不一致",
				error.getMessage());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("temperatureOmittingDialects")
	void validatePersistableRejectsTemperatureSendForDeepSeekAndMoonshot(ModelEndpointDialect dialect) {
		ModelConfigDTO config = config(dialect.name());
		config.setTemperaturePolicy(ModelTemperaturePolicy.SEND.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.validatePersistable(config));

		assertEquals("temperaturePolicy 的持久化值 SEND 与实际生效值 OMIT 不一致", error.getMessage());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(ModelEndpointDialect.class)
	void resolveRejectsKeepForEveryDialect(ModelEndpointDialect dialect) {
		ModelConfigDTO config = config(dialect.name());
		config.setPreservedReasoningPolicy(ModelPreservedReasoningPolicy.KEEP.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(config));

		assertEquals("当前不支持 preservedReasoningPolicy=KEEP：缺少跨轮 reasoning 历史消费者", error.getMessage());
	}

	@Test
	void resolveRejectsTaskScopedKeepOverride() {
		ModelConfigDTO config = config(ModelEndpointDialect.OPENAI_COMPATIBLE.name());
		ModelRequestOptionsOverride override = ModelRequestOptionsOverride.builder()
			.preservedReasoningPolicy(ModelPreservedReasoningPolicy.KEEP)
			.build();

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(config, override, null));

		assertEquals("当前不支持 preservedReasoningPolicy=KEEP：缺少跨轮 reasoning 历史消费者", error.getMessage());
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(ModelEndpointDialect.class)
	void validatePersistableRejectsKeepForEveryChatDialect(ModelEndpointDialect dialect) {
		ModelConfigDTO config = config(dialect.name());
		config.setPreservedReasoningPolicy(ModelPreservedReasoningPolicy.KEEP.name());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> resolver.validatePersistable(config));

		assertEquals("当前不支持 preservedReasoningPolicy=KEEP：缺少跨轮 reasoning 历史消费者", error.getMessage());
	}

	@Test
	void validatePersistableAcceptsExplicitSupportedOptions() {
		ModelConfigDTO config = config(ModelEndpointDialect.STEPFUN_NATIVE.name());
		config.setReasoningProtocol(ModelReasoningProtocol.REASONING_EFFORT.name());
		config.setReasoningMode(ModelReasoningMode.ENABLED.name());
		config.setReasoningLevel(ModelReasoningLevel.HIGH.name());
		config.setTokenLimitMode(ModelTokenLimitMode.MAX_TOKENS.name());
		config.setTemperaturePolicy(ModelTemperaturePolicy.SEND.name());
		config.setStructuredOutputMode(ModelStructuredOutputMode.STRICT_JSON_SCHEMA.name());
		config.setPreservedReasoningPolicy(ModelPreservedReasoningPolicy.DROP.name());

		ResolvedModelRequestOptions resolved = resolver.validatePersistable(config);

		assertEquals(ModelReasoningProtocol.REASONING_EFFORT, resolved.reasoningProtocol());
		assertEquals(ModelReasoningMode.ENABLED, resolved.reasoningMode());
		assertEquals(ModelReasoningLevel.HIGH, resolved.reasoningLevel());
		assertEquals(ModelTokenLimitMode.MAX_TOKENS, resolved.tokenLimitMode());
		assertEquals(ModelTemperaturePolicy.SEND, resolved.temperaturePolicy());
		assertEquals(ModelStructuredOutputMode.STRICT_JSON_SCHEMA, resolved.structuredOutputMode());
		assertEquals(ModelPreservedReasoningPolicy.DROP, resolved.preservedReasoningPolicy());
	}

	@Test
	void capabilityDescriptorsContainEveryValidDialectProfilePairInEnumOrder() {
		List<ModelCapabilityDescriptorResp> descriptors = resolver.capabilityDescriptors();

		assertEquals(22, descriptors.size());
		assertEquals(ModelEndpointDialect.OPENAI_COMPATIBLE, descriptors.get(0).endpointDialect());
		assertEquals(ModelCapabilityProfile.AUTO, descriptors.get(0).capabilityProfile());
		assertEquals(ModelEndpointDialect.OPENAI_COMPATIBLE, descriptors.get(1).endpointDialect());
		assertEquals(ModelCapabilityProfile.NO_REASONING, descriptors.get(1).capabilityProfile());
		assertEquals(ModelEndpointDialect.DASHSCOPE_NATIVE, descriptors.get(2).endpointDialect());
		assertEquals(ModelCapabilityProfile.AUTO, descriptors.get(2).capabilityProfile());
		assertEquals(ModelEndpointDialect.CUSTOM, descriptors.get(20).endpointDialect());
		assertEquals(ModelCapabilityProfile.AUTO, descriptors.get(20).capabilityProfile());
		assertEquals(ModelCapabilityProfile.NO_REASONING, descriptors.get(21).capabilityProfile());
	}

	@Test
	void deepSeekDescriptorOnlyExposesPersistableProfileOptions() {
		ModelCapabilityDescriptorResp descriptor = descriptor(ModelEndpointDialect.DEEPSEEK_NATIVE,
				ModelCapabilityProfile.DEEPSEEK_THINKING);

		assertEquals(List.of(ModelReasoningProtocol.AUTO, ModelReasoningProtocol.THINKING_OBJECT_WITH_EFFORT),
				descriptor.reasoningProtocols());
		assertEquals(List.of(ModelReasoningMode.AUTO, ModelReasoningMode.ENABLED, ModelReasoningMode.DISABLED),
				descriptor.reasoningModes());
		assertEquals(List.of(ModelReasoningLevel.LOW, ModelReasoningLevel.HIGH, ModelReasoningLevel.MAX),
				descriptor.reasoningLevels());
		assertEquals(false, descriptor.reasoningBudgetSupported());
		assertEquals(true, descriptor.reasoningEffortSupported());
		assertEquals(true, descriptor.reasoningDisableSupported());
		assertEquals(List.of(ModelTokenLimitMode.MAX_TOKENS), descriptor.tokenLimitModes());
		assertEquals(List.of(ModelTemperaturePolicy.OMIT), descriptor.temperaturePolicies());
		assertEquals(ModelReasoningProtocol.THINKING_OBJECT_WITH_EFFORT,
				descriptor.defaults().reasoningProtocol());
		assertEquals(ModelTemperaturePolicy.OMIT, descriptor.defaults().temperaturePolicy());
	}

	@Test
	void kimiK3AndQwenThinkingOnlyDoNotExposeImplicitDisableOptions() {
		ModelCapabilityDescriptorResp kimi = descriptor(ModelEndpointDialect.MOONSHOT_NATIVE,
				ModelCapabilityProfile.KIMI_K3_REASONING);
		assertEquals(List.of(ModelReasoningProtocol.AUTO, ModelReasoningProtocol.REASONING_EFFORT),
				kimi.reasoningProtocols());
		assertEquals(List.of(ModelReasoningMode.AUTO, ModelReasoningMode.ENABLED), kimi.reasoningModes());
		assertEquals(List.of(ModelReasoningLevel.LOW, ModelReasoningLevel.HIGH, ModelReasoningLevel.MAX),
				kimi.reasoningLevels());
		assertEquals(false, kimi.reasoningDisableSupported());

		ModelCapabilityDescriptorResp qwen = descriptor(ModelEndpointDialect.DASHSCOPE_NATIVE,
				ModelCapabilityProfile.QWEN_THINKING_ONLY);
		assertEquals(List.of(ModelReasoningProtocol.AUTO, ModelReasoningProtocol.ENABLE_THINKING_ONLY),
				qwen.reasoningProtocols());
		assertEquals(List.of(ModelReasoningMode.AUTO, ModelReasoningMode.ENABLED), qwen.reasoningModes());
		assertEquals(List.of(), qwen.reasoningLevels());
		assertEquals(false, qwen.reasoningDisableSupported());
	}

	@Test
	void noReasoningDescriptorPreservesDialectOptionsWithoutExposingReasoning() {
		ModelCapabilityDescriptorResp descriptor = descriptor(ModelEndpointDialect.OPENAI_COMPATIBLE,
				ModelCapabilityProfile.NO_REASONING);

		assertEquals(List.of(ModelReasoningProtocol.AUTO, ModelReasoningProtocol.NONE),
				descriptor.reasoningProtocols());
		assertEquals(List.of(ModelReasoningMode.AUTO, ModelReasoningMode.DISABLED), descriptor.reasoningModes());
		assertEquals(List.of(), descriptor.reasoningLevels());
		assertEquals(false, descriptor.reasoningBudgetSupported());
		assertEquals(false, descriptor.reasoningEffortSupported());
		assertEquals(true, descriptor.reasoningDisableSupported());
		assertEquals(List.of(ModelTokenLimitMode.MAX_TOKENS, ModelTokenLimitMode.MAX_COMPLETION_TOKENS),
				descriptor.tokenLimitModes());
		assertEquals(List.of(ModelTemperaturePolicy.SEND, ModelTemperaturePolicy.OMIT),
				descriptor.temperaturePolicies());
		assertEquals(ModelReasoningProtocol.NONE, descriptor.defaults().reasoningProtocol());
		assertEquals(ModelReasoningMode.DISABLED, descriptor.defaults().reasoningMode());
	}

	@Test
	void capabilityDescriptorsAllowAutoStructuredOutputAndNeverExposeKeep() {
		for (ModelCapabilityDescriptorResp descriptor : resolver.capabilityDescriptors()) {
			assertEquals(ModelStructuredOutputMode.AUTO, descriptor.structuredOutputModes().get(0));
			assertEquals(List.of(ModelPreservedReasoningPolicy.DROP), descriptor.preservedReasoningPolicies());
			assertEquals(ModelPreservedReasoningPolicy.DROP, descriptor.defaults().preservedReasoningPolicy());
		}
	}

	private ModelCapabilityDescriptorResp descriptor(ModelEndpointDialect dialect, ModelCapabilityProfile profile) {
		return resolver.capabilityDescriptors()
			.stream()
			.filter(item -> item.endpointDialect() == dialect && item.capabilityProfile() == profile)
			.findFirst()
			.orElseThrow();
	}

	private static Stream<Arguments> nativeDialectDefaults() {
		return Stream.of(
				Arguments.of(ModelEndpointDialect.DASHSCOPE_NATIVE, ModelReasoningProtocol.ENABLE_THINKING, true,
						ModelTemperaturePolicy.SEND, true),
				Arguments.of(ModelEndpointDialect.STEPFUN_NATIVE, ModelReasoningProtocol.REASONING_EFFORT, false,
						ModelTemperaturePolicy.SEND, true),
				Arguments.of(ModelEndpointDialect.DEEPSEEK_NATIVE, ModelReasoningProtocol.THINKING_OBJECT, false,
						ModelTemperaturePolicy.OMIT, false),
				Arguments.of(ModelEndpointDialect.ZHIPU_NATIVE, ModelReasoningProtocol.THINKING_OBJECT, true,
						ModelTemperaturePolicy.SEND, true),
				Arguments.of(ModelEndpointDialect.MOONSHOT_NATIVE, ModelReasoningProtocol.THINKING_OBJECT, false,
						ModelTemperaturePolicy.OMIT, false));
	}

	private static Stream<Arguments> nativeReasoningWireShapes() {
		Map<String, Object> thinking = Map.of("thinking", Map.of("type", "enabled", "budget_tokens", 512L));
		return Stream.of(
				Arguments.of(ModelEndpointDialect.DASHSCOPE_NATIVE, null, 512L,
						Map.of("enable_thinking", true, "thinking_budget", 512L), null),
				Arguments.of(ModelEndpointDialect.STEPFUN_NATIVE, ModelReasoningLevel.MEDIUM, null, Map.of(), "medium"),
				Arguments.of(ModelEndpointDialect.ZHIPU_NATIVE, null, 512L, thinking, null),
				Arguments.of(ModelEndpointDialect.MOONSHOT_NATIVE, null, null,
						Map.of("thinking", Map.of("type", "enabled")), null));
	}

	private static Stream<Arguments> foreignNativeProtocols() {
		return Stream.of(
				Arguments.of(ModelEndpointDialect.DASHSCOPE_NATIVE, ModelReasoningProtocol.THINKING_OBJECT),
				Arguments.of(ModelEndpointDialect.STEPFUN_NATIVE, ModelReasoningProtocol.ENABLE_THINKING),
				Arguments.of(ModelEndpointDialect.DEEPSEEK_NATIVE, ModelReasoningProtocol.REASONING_EFFORT),
				Arguments.of(ModelEndpointDialect.ZHIPU_NATIVE, ModelReasoningProtocol.ENABLE_THINKING),
				Arguments.of(ModelEndpointDialect.MOONSHOT_NATIVE, ModelReasoningProtocol.REASONING_EFFORT));
	}

	private static Stream<ModelEndpointDialect> temperatureOmittingDialects() {
		return Stream.of(ModelEndpointDialect.DEEPSEEK_NATIVE, ModelEndpointDialect.MOONSHOT_NATIVE);
	}

	private static Stream<ModelEndpointDialect> booleanReasoningDialects() {
		return Stream.of(ModelEndpointDialect.DASHSCOPE_NATIVE,
				ModelEndpointDialect.ZHIPU_NATIVE, ModelEndpointDialect.MOONSHOT_NATIVE);
	}

	private ModelConfigDTO config(String dialect) {
		return ModelConfigDTO.builder().provider("test").apiKey("key").baseUrl("https://example.com")
			.modelName("test-model").modelType("CHAT").endpointDialect(dialect).temperature(0.2D)
			.maxTokens(2000L).build();
	}

}
