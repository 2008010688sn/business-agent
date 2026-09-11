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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Resolves task overrides, model defaults, dialect defaults, and probe capabilities
 * into a provider-safe request option set.
 */
@Component
public class ModelRequestOptionsResolver {

	private final Map<ModelEndpointDialect, ModelEndpointDialectAdapter> adapters;

	public ModelRequestOptionsResolver() {
		EnumMap<ModelEndpointDialect, ModelEndpointDialectAdapter> registered = new EnumMap<>(
				ModelEndpointDialect.class);
		registered.put(ModelEndpointDialect.OPENAI_COMPATIBLE,
				adapter(ModelEndpointDialect.OPENAI_COMPATIBLE, ModelReasoningProtocol.NONE,
						ModelTemperaturePolicy.SEND, ModelStructuredOutputMode.STRICT_JSON_SCHEMA));
		registered.put(ModelEndpointDialect.DASHSCOPE_NATIVE,
				nativeAdapter(ModelEndpointDialect.DASHSCOPE_NATIVE, ModelReasoningProtocol.ENABLE_THINKING, true, false,
						true, ModelTokenAccounting.MAX_TOKENS_INCLUDES_REASONING, ModelTemperaturePolicy.SEND));
		registered.put(ModelEndpointDialect.STEPFUN_NATIVE,
				nativeAdapter(ModelEndpointDialect.STEPFUN_NATIVE, ModelReasoningProtocol.REASONING_EFFORT, false, true,
						false, ModelTokenAccounting.UNKNOWN, ModelTemperaturePolicy.SEND));
		registered.put(ModelEndpointDialect.DEEPSEEK_NATIVE,
				nativeAdapter(ModelEndpointDialect.DEEPSEEK_NATIVE, ModelReasoningProtocol.THINKING_OBJECT, false, false,
						true, ModelTokenAccounting.UNKNOWN, ModelTemperaturePolicy.OMIT));
		registered.put(ModelEndpointDialect.ZHIPU_NATIVE,
				nativeAdapter(ModelEndpointDialect.ZHIPU_NATIVE, ModelReasoningProtocol.THINKING_OBJECT, true, false,
						true, ModelTokenAccounting.MAX_TOKENS_INCLUDES_REASONING, ModelTemperaturePolicy.SEND));
		registered.put(ModelEndpointDialect.MOONSHOT_NATIVE,
				nativeAdapter(ModelEndpointDialect.MOONSHOT_NATIVE, ModelReasoningProtocol.THINKING_OBJECT, false, false,
						true, ModelTokenAccounting.UNKNOWN, ModelTemperaturePolicy.OMIT));
		registered.put(ModelEndpointDialect.CUSTOM,
				adapter(ModelEndpointDialect.CUSTOM, ModelReasoningProtocol.NONE,
						ModelTemperaturePolicy.SEND, ModelStructuredOutputMode.PROMPT_JSON));
		this.adapters = Map.copyOf(registered);
	}

	public ResolvedModelRequestOptions resolve(ModelConfigDTO config) {
		return resolve(config, ModelRequestOptionsOverride.none(), null);
	}

	/**
	 * 校验待持久化的模型配置，拒绝保存后会被运行时静默改写的显式选项。
	 */
	public ResolvedModelRequestOptions validatePersistable(ModelConfigDTO config) {
		ResolvedModelRequestOptions resolved = resolve(config);
		ModelPreservedReasoningPolicy preservedReasoningPolicy = parse(config.getPreservedReasoningPolicy(),
				ModelPreservedReasoningPolicy.class, null);

		requireUnchanged("reasoningProtocol",
				parse(config.getReasoningProtocol(), ModelReasoningProtocol.class, ModelReasoningProtocol.AUTO),
				ModelReasoningProtocol.AUTO, resolved.reasoningProtocol());
		requireUnchanged("reasoningMode",
				parse(config.getReasoningMode(), ModelReasoningMode.class, ModelReasoningMode.AUTO),
				ModelReasoningMode.AUTO, resolved.reasoningMode());
		requireUnchanged("reasoningLevel",
				parse(config.getReasoningLevel(), ModelReasoningLevel.class, null), null, resolved.reasoningLevel());
		requireUnchanged("reasoningBudgetTokens", config.getReasoningBudgetTokens(), null,
				resolved.reasoningBudgetTokens());
		requireUnchanged("tokenLimitMode", parse(config.getTokenLimitMode(), ModelTokenLimitMode.class, null), null,
				resolved.tokenLimitMode());
		requireUnchanged("temperaturePolicy",
				parse(config.getTemperaturePolicy(), ModelTemperaturePolicy.class, null), null,
				resolved.temperaturePolicy());
		requireUnchanged("structuredOutputMode",
				parse(config.getStructuredOutputMode(), ModelStructuredOutputMode.class, ModelStructuredOutputMode.AUTO),
				ModelStructuredOutputMode.AUTO, resolved.structuredOutputMode());
		requireUnchanged("preservedReasoningPolicy", preservedReasoningPolicy, null,
				resolved.preservedReasoningPolicy());
		return resolved;
	}

	/**
	 * Profile-aware dialect capabilities used to apply optional FLOW preferences
	 * without failing the request when a vendor cannot disable reasoning.
	 */
	public ModelRequestCapabilities capabilities(ModelConfigDTO config) {
		Objects.requireNonNull(config, "model config must not be null");
		ModelEndpointDialect dialect = parse(config.getEndpointDialect(), ModelEndpointDialect.class,
				ModelEndpointDialect.OPENAI_COMPATIBLE);
		return resolveAdapter(config, dialect).capabilities();
	}

	/**
	 * 按「任务覆盖 &gt; 模型配置 &gt; 方言默认」的优先级归并请求选项，并用能力探测结果收敛到 provider 可接受的取值。
	 * 命中方言不支持的显式选项时抛出 {@link IllegalArgumentException}。
	 */
	public ResolvedModelRequestOptions resolve(ModelConfigDTO config, ModelRequestOptionsOverride override,
			ModelRequestCapabilities probedCapabilities) {
		Objects.requireNonNull(config, "model config must not be null");
		ModelRequestOptionsOverride task = override == null ? ModelRequestOptionsOverride.none() : override;
		ModelEndpointDialect dialect = parse(config.getEndpointDialect(), ModelEndpointDialect.class,
				ModelEndpointDialect.OPENAI_COMPATIBLE);
		ModelEndpointDialectAdapter adapter = resolveAdapter(config, dialect);
		ModelDialectDefaults defaults = adapter.defaults();
		ModelRequestCapabilities capabilities = clampCapabilities(adapter.capabilities(), probedCapabilities);

		ReasoningSettings reasoning = resolveReasoningSettings(config, task, dialect, defaults, capabilities);
		Long budget = resolveClampedBudget(config, task, dialect, capabilities, reasoning);

		ModelTokenLimitMode tokenLimitMode = resolveTokenLimit(config, task, defaults);
		if (!capabilities.tokenLimitModes().contains(tokenLimitMode)) {
			tokenLimitMode = first(capabilities.tokenLimitModes());
		}
		Integer maxOutputTokens = toInteger(task.maxOutputTokens() != null ? task.maxOutputTokens() : config.getMaxTokens());
		if (tokenLimitMode == null) {
			maxOutputTokens = null;
		}

		ModelTemperaturePolicy temperaturePolicy = resolveTemperaturePolicy(config, task, defaults);
		if (!Boolean.TRUE.equals(capabilities.temperatureSupported())) {
			temperaturePolicy = ModelTemperaturePolicy.OMIT;
		}
		Double temperature = temperaturePolicy == ModelTemperaturePolicy.SEND
				? (task.temperature() != null ? task.temperature() : config.getTemperature()) : null;

		ModelStructuredOutputMode structuredOutputMode = resolveClampedStructuredOutput(config, task, dialect, defaults,
				capabilities);
		ModelPreservedReasoningPolicy preservedReasoningPolicy = resolveClampedPreservedReasoning(config, task, defaults,
				capabilities);

		ModelLogicalRequestOptions logical = new ModelLogicalRequestOptions(dialect, reasoning.protocol(),
				reasoning.mode(), reasoning.level(), budget,
				maxOutputTokens, tokenLimitMode, capabilities.tokenAccounting(), temperature, temperaturePolicy,
				structuredOutputMode,
				preservedReasoningPolicy);
		return adapter.adapt(logical);
	}

	/**
	 * 归并推理协议/模式/等级三元组并按能力收敛，显式选项不受方言支持时抛出异常。
	 */
	private ReasoningSettings resolveReasoningSettings(ModelConfigDTO config, ModelRequestOptionsOverride task,
			ModelEndpointDialect dialect, ModelDialectDefaults defaults, ModelRequestCapabilities capabilities) {
		ModelReasoningProtocol protocol = resolveProtocol(config, task, defaults);
		protocol = requireSupportedProtocol(dialect, protocol, capabilities.reasoningProtocols());
		if (requiresReasoningEffort(protocol) && !Boolean.TRUE.equals(capabilities.reasoningEffortSupported())) {
			throw new IllegalArgumentException("reasoning effort is not supported by " + dialect);
		}
		ModelReasoningMode mode = resolveMode(config, task, defaults);
		mode = clampMode(mode, defaults.reasoningMode(), capabilities.reasoningModes());
		ModelReasoningLevel level = resolveLevel(config, task, null);
		if (level == ModelReasoningLevel.NONE && mode == ModelReasoningMode.AUTO) {
			mode = ModelReasoningMode.DISABLED;
		}
		if (protocol == ModelReasoningProtocol.NONE) {
			mode = ModelReasoningMode.DISABLED;
			level = ModelReasoningLevel.NONE;
		}
		if (mode == ModelReasoningMode.DISABLED && protocol == ModelReasoningProtocol.REASONING_EFFORT
				&& !capabilities.reasoningLevels().contains(ModelReasoningLevel.NONE)) {
			mode = clampMode(ModelReasoningMode.AUTO, defaults.reasoningMode(), capabilities.reasoningModes());
		}
		if (mode == ModelReasoningMode.DISABLED && protocol != ModelReasoningProtocol.NONE
				&& !Boolean.TRUE.equals(capabilities.reasoningDisableSupported())) {
			throw new IllegalArgumentException("reasoning disable is not supported by " + dialect);
		}
		level = clampLevel(dialect, level, mode, capabilities.reasoningLevels());
		if (dialect == ModelEndpointDialect.STEPFUN_NATIVE && protocol == ModelReasoningProtocol.REASONING_EFFORT
				&& mode == ModelReasoningMode.ENABLED && level == null) {
			throw new IllegalArgumentException("reasoningLevel is required when STEPFUN_NATIVE reasoning is enabled");
		}
		return new ReasoningSettings(protocol, mode, level);
	}

	/**
	 * 归并推理预算：不支持预算的方言直接拒绝，协议不消费预算或推理被禁用时清空。
	 */
	private Long resolveClampedBudget(ModelConfigDTO config, ModelRequestOptionsOverride task,
			ModelEndpointDialect dialect, ModelRequestCapabilities capabilities, ReasoningSettings reasoning) {
		Long budget = resolveBudget(config, task);
		if (budget != null && !Boolean.TRUE.equals(capabilities.reasoningBudgetSupported())) {
			throw new IllegalArgumentException("reasoningBudgetTokens is not supported by " + dialect);
		}
		if (budget != null && reasoning.protocol() != ModelReasoningProtocol.ENABLE_THINKING
				&& reasoning.protocol() != ModelReasoningProtocol.ENABLE_THINKING_ONLY
				&& reasoning.protocol() != ModelReasoningProtocol.THINKING_OBJECT
				&& reasoning.protocol() != ModelReasoningProtocol.THINKING_OBJECT_WITH_EFFORT) {
			budget = null;
		}
		if (reasoning.mode() == ModelReasoningMode.DISABLED) {
			budget = null;
		}
		return budget;
	}

	/**
	 * 归并结构化输出模式并按能力收敛，任务显式指定却与收敛结果不一致时抛出异常。
	 */
	private ModelStructuredOutputMode resolveClampedStructuredOutput(ModelConfigDTO config,
			ModelRequestOptionsOverride task, ModelEndpointDialect dialect, ModelDialectDefaults defaults,
			ModelRequestCapabilities capabilities) {
		ModelStructuredOutputMode structuredOutputMode = resolveStructuredOutput(config, task, defaults);
		structuredOutputMode = clampStructuredOutput(structuredOutputMode, capabilities.structuredOutputModes());
		if (task.structuredOutputMode() != null && task.structuredOutputMode() != ModelStructuredOutputMode.AUTO
				&& task.structuredOutputMode() != structuredOutputMode) {
			throw new IllegalArgumentException("structuredOutputMode " + task.structuredOutputMode()
					+ " is not supported by " + dialect);
		}
		return structuredOutputMode;
	}

	/**
	 * 归并保留推理策略：KEEP 目前无消费方直接拒绝，能力不支持时回退 DROP。
	 */
	private ModelPreservedReasoningPolicy resolveClampedPreservedReasoning(ModelConfigDTO config,
			ModelRequestOptionsOverride task, ModelDialectDefaults defaults, ModelRequestCapabilities capabilities) {
		ModelPreservedReasoningPolicy preservedReasoningPolicy = resolvePreservedReasoning(config, task, defaults);
		if (preservedReasoningPolicy == ModelPreservedReasoningPolicy.KEEP) {
			throw new IllegalArgumentException("当前不支持 preservedReasoningPolicy=KEEP：缺少跨轮 reasoning 历史消费者");
		}
		if (!capabilities.preservedReasoningPolicies().contains(preservedReasoningPolicy)) {
			preservedReasoningPolicy = ModelPreservedReasoningPolicy.DROP;
		}
		return preservedReasoningPolicy;
	}

	/**
	 * 推理协议/模式/等级归并结果。
	 */
	private record ReasoningSettings(ModelReasoningProtocol protocol, ModelReasoningMode mode,
			ModelReasoningLevel level) {
	}

	public ModelEndpointDialectAdapter adapter(ModelEndpointDialect dialect) {
		return adapters.getOrDefault(dialect, adapters.get(ModelEndpointDialect.CUSTOM));
	}

	/**
	 * Returns the persistable option surface for every valid dialect/Profile pair.
	 * The same adapter construction used by runtime resolution remains the sole
	 * source of provider compatibility rules.
	 */
	public List<ModelCapabilityDescriptorResp> capabilityDescriptors() {
		List<ModelCapabilityDescriptorResp> descriptors = new ArrayList<>();
		for (ModelEndpointDialect dialect : ModelEndpointDialect.values()) {
			for (ModelCapabilityProfile profile : ModelCapabilityProfile.values()) {
				try {
					ModelEndpointDialectAdapter adapter = resolveAdapter(
							ModelConfigDTO.builder().endpointDialect(dialect.name()).capabilityProfile(profile.name()).build(),
							dialect);
					descriptors.add(toDescriptor(dialect, profile, adapter));
				}
				catch (IllegalArgumentException ignored) {
					// Named Profiles are intentionally valid only for their native dialect.
				}
			}
		}
		return List.copyOf(descriptors);
	}

	private ModelCapabilityDescriptorResp toDescriptor(ModelEndpointDialect dialect, ModelCapabilityProfile profile,
			ModelEndpointDialectAdapter adapter) {
		ModelDialectDefaults defaults = adapter.defaults();
		ModelRequestCapabilities capabilities = adapter.capabilities();
		EnumSet<ModelReasoningProtocol> protocols = copy(ModelReasoningProtocol.class, capabilities.reasoningProtocols());
		protocols.add(ModelReasoningProtocol.AUTO);
		EnumSet<ModelReasoningMode> modes = copy(ModelReasoningMode.class, capabilities.reasoningModes());
		modes.add(ModelReasoningMode.AUTO);
		if (!Boolean.TRUE.equals(capabilities.reasoningDisableSupported())) {
			modes.remove(ModelReasoningMode.DISABLED);
		}
		EnumSet<ModelReasoningLevel> levels = copy(ModelReasoningLevel.class, capabilities.reasoningLevels());
		levels.remove(ModelReasoningLevel.NONE);
		EnumSet<ModelTemperaturePolicy> temperatures = EnumSet.of(ModelTemperaturePolicy.OMIT);
		if (Boolean.TRUE.equals(capabilities.temperatureSupported())) {
			temperatures.add(ModelTemperaturePolicy.SEND);
		}
		EnumSet<ModelStructuredOutputMode> structuredOutputs =
				copy(ModelStructuredOutputMode.class, capabilities.structuredOutputModes());
		structuredOutputs.add(ModelStructuredOutputMode.AUTO);
		return new ModelCapabilityDescriptorResp(dialect, profile,
				ordered(ModelReasoningProtocol.class, protocols), ordered(ModelReasoningMode.class, modes),
				ordered(ModelReasoningLevel.class, levels), Boolean.TRUE.equals(capabilities.reasoningBudgetSupported()),
				Boolean.TRUE.equals(capabilities.reasoningEffortSupported()),
				Boolean.TRUE.equals(capabilities.reasoningDisableSupported()),
				ordered(ModelTokenLimitMode.class, capabilities.tokenLimitModes()),
				ordered(ModelTemperaturePolicy.class, temperatures),
				ordered(ModelStructuredOutputMode.class, structuredOutputs),
				List.of(ModelPreservedReasoningPolicy.DROP),
				new ModelCapabilityDescriptorResp.Defaults(defaults.reasoningProtocol(), defaults.reasoningMode(),
						defaults.reasoningLevel(), defaults.tokenLimitMode(), defaults.temperaturePolicy(),
						defaults.structuredOutputMode(), ModelPreservedReasoningPolicy.DROP));
	}

	private static <E extends Enum<E>> EnumSet<E> copy(Class<E> type, Set<E> values) {
		EnumSet<E> result = EnumSet.noneOf(type);
		if (values != null) {
			result.addAll(values);
		}
		return result;
	}

	private static <E extends Enum<E>> List<E> ordered(Class<E> type, Set<E> values) {
		return Arrays.stream(type.getEnumConstants()).filter(values::contains).toList();
	}

	private ModelEndpointDialectAdapter resolveAdapter(ModelConfigDTO config, ModelEndpointDialect dialect) {
		ModelCapabilityProfile profile = parse(config.getCapabilityProfile(), ModelCapabilityProfile.class,
				ModelCapabilityProfile.AUTO);
		if (profile == ModelCapabilityProfile.AUTO) {
			return adapter(dialect);
		}
		return switch (profile) {
			case NO_REASONING -> noReasoningAdapter(dialect);
			case QWEN_HYBRID -> profileAdapter(profile, dialect, ModelEndpointDialect.DASHSCOPE_NATIVE,
					ModelReasoningProtocol.ENABLE_THINKING, true, false, true,
					ModelTokenAccounting.MAX_TOKENS_INCLUDES_REASONING, ModelTemperaturePolicy.SEND);
			case QWEN_THINKING_ONLY -> profileAdapter(profile, dialect, ModelEndpointDialect.DASHSCOPE_NATIVE,
					ModelReasoningProtocol.ENABLE_THINKING_ONLY, true, false, false,
					ModelTokenAccounting.MAX_TOKENS_INCLUDES_REASONING, ModelTemperaturePolicy.SEND);
			case STEPFUN_REASONING -> profileAdapter(profile, dialect, ModelEndpointDialect.STEPFUN_NATIVE,
					ModelReasoningProtocol.REASONING_EFFORT, false, true, false,
					ModelTokenAccounting.UNKNOWN, ModelTemperaturePolicy.SEND);
			case DEEPSEEK_THINKING -> profileAdapter(profile, dialect, ModelEndpointDialect.DEEPSEEK_NATIVE,
					ModelReasoningProtocol.THINKING_OBJECT_WITH_EFFORT, false, true, true,
					ModelTokenAccounting.UNKNOWN, ModelTemperaturePolicy.OMIT);
			case GLM_THINKING -> profileAdapter(profile, dialect, ModelEndpointDialect.ZHIPU_NATIVE,
					ModelReasoningProtocol.THINKING_OBJECT, true, false, true,
					ModelTokenAccounting.MAX_TOKENS_INCLUDES_REASONING, ModelTemperaturePolicy.SEND);
			case KIMI_K3_REASONING -> profileAdapter(profile, dialect, ModelEndpointDialect.MOONSHOT_NATIVE,
					ModelReasoningProtocol.REASONING_EFFORT, false, true, false,
					ModelTokenAccounting.UNKNOWN, ModelTemperaturePolicy.OMIT);
			case KIMI_K26_THINKING -> profileAdapter(profile, dialect, ModelEndpointDialect.MOONSHOT_NATIVE,
					ModelReasoningProtocol.THINKING_OBJECT, false, false, true,
					ModelTokenAccounting.UNKNOWN, ModelTemperaturePolicy.OMIT);
			case KIMI_K27_CODE -> profileAdapter(profile, dialect, ModelEndpointDialect.MOONSHOT_NATIVE,
					ModelReasoningProtocol.THINKING_OBJECT, false, false, false,
					ModelTokenAccounting.UNKNOWN, ModelTemperaturePolicy.OMIT);
			case AUTO -> throw new IllegalStateException("AUTO capability profile must use dialect defaults");
		};
	}

	private ModelEndpointDialectAdapter profileAdapter(ModelCapabilityProfile profile,
			ModelEndpointDialect actualDialect, ModelEndpointDialect requiredDialect,
			ModelReasoningProtocol protocol, boolean reasoningBudgetSupported,
			boolean reasoningEffortSupported, boolean reasoningDisableSupported,
			ModelTokenAccounting tokenAccounting, ModelTemperaturePolicy temperaturePolicy) {
		if (actualDialect != requiredDialect) {
			throw new IllegalArgumentException("capabilityProfile " + profile + " requires endpointDialect "
					+ requiredDialect);
		}
		ModelReasoningMode defaultMode = switch (profile) {
			case QWEN_THINKING_ONLY, KIMI_K3_REASONING, KIMI_K27_CODE -> ModelReasoningMode.ENABLED;
			case AUTO, NO_REASONING, QWEN_HYBRID, STEPFUN_REASONING, DEEPSEEK_THINKING, GLM_THINKING,
					KIMI_K26_THINKING -> ModelReasoningMode.AUTO;
		};
		return nativeAdapter(actualDialect, protocol, reasoningBudgetSupported, reasoningEffortSupported,
				reasoningDisableSupported, tokenAccounting, temperaturePolicy, defaultMode);
	}

	private ModelEndpointDialectAdapter noReasoningAdapter(ModelEndpointDialect dialect) {
		ModelEndpointDialectAdapter base = adapter(dialect);
		ModelDialectDefaults defaults = base.defaults();
		ModelRequestCapabilities capabilities = base.capabilities();
		ModelDialectDefaults noReasoningDefaults = new ModelDialectDefaults(ModelReasoningProtocol.NONE,
				ModelReasoningMode.DISABLED, ModelReasoningLevel.NONE, defaults.tokenLimitMode(),
				defaults.temperaturePolicy(), defaults.structuredOutputMode(), defaults.preservedReasoningPolicy());
		ModelRequestCapabilities noReasoningCapabilities = ModelRequestCapabilities.builder()
			.reasoningProtocols(EnumSet.of(ModelReasoningProtocol.NONE))
			.reasoningModes(EnumSet.of(ModelReasoningMode.DISABLED))
			.reasoningLevels(EnumSet.of(ModelReasoningLevel.NONE))
			.reasoningBudgetSupported(false)
			.reasoningEffortSupported(false)
			.reasoningDisableSupported(true)
			.tokenAccounting(ModelTokenAccounting.OUTPUT_ONLY)
			.tokenLimitModes(capabilities.tokenLimitModes())
			.temperatureSupported(capabilities.temperatureSupported())
			.structuredOutputModes(capabilities.structuredOutputModes())
			.preservedReasoningPolicies(capabilities.preservedReasoningPolicies())
			.build();
		return new ModelEndpointDialectAdapter(dialect, noReasoningDefaults, noReasoningCapabilities);
	}

	private ModelEndpointDialectAdapter adapter(ModelEndpointDialect dialect, ModelReasoningProtocol protocol,
			ModelTemperaturePolicy temperaturePolicy, ModelStructuredOutputMode structuredOutputMode) {
		ModelDialectDefaults defaults = new ModelDialectDefaults(protocol, ModelReasoningMode.AUTO, null,
				ModelTokenLimitMode.MAX_TOKENS, temperaturePolicy, structuredOutputMode,
				ModelPreservedReasoningPolicy.DROP);
		return new ModelEndpointDialectAdapter(dialect, defaults, unconstrainedCapabilities());
	}

	private ModelEndpointDialectAdapter nativeAdapter(ModelEndpointDialect dialect, ModelReasoningProtocol protocol,
			boolean reasoningBudgetSupported, boolean reasoningEffortSupported, boolean reasoningDisableSupported,
			ModelTokenAccounting tokenAccounting, ModelTemperaturePolicy temperaturePolicy) {
		return nativeAdapter(dialect, protocol, reasoningBudgetSupported, reasoningEffortSupported,
				reasoningDisableSupported, tokenAccounting, temperaturePolicy, ModelReasoningMode.AUTO);
	}

	private ModelEndpointDialectAdapter nativeAdapter(ModelEndpointDialect dialect, ModelReasoningProtocol protocol,
			boolean reasoningBudgetSupported, boolean reasoningEffortSupported, boolean reasoningDisableSupported,
			ModelTokenAccounting tokenAccounting, ModelTemperaturePolicy temperaturePolicy,
			ModelReasoningMode defaultMode) {
		ModelDialectDefaults defaults = new ModelDialectDefaults(protocol, defaultMode, null,
				ModelTokenLimitMode.MAX_TOKENS, temperaturePolicy, ModelStructuredOutputMode.JSON_OBJECT,
				ModelPreservedReasoningPolicy.DROP);
		return new ModelEndpointDialectAdapter(dialect, defaults,
					nativeCapabilities(dialect, protocol, reasoningBudgetSupported, reasoningEffortSupported,
						reasoningDisableSupported, tokenAccounting, temperaturePolicy == ModelTemperaturePolicy.SEND));
	}

	private ModelRequestCapabilities nativeCapabilities(ModelEndpointDialect dialect, ModelReasoningProtocol protocol,
			boolean reasoningBudgetSupported, boolean reasoningEffortSupported, boolean reasoningDisableSupported,
			ModelTokenAccounting tokenAccounting, boolean temperatureSupported) {
		Set<ModelReasoningLevel> reasoningLevels;
		if (dialect == ModelEndpointDialect.DEEPSEEK_NATIVE && reasoningEffortSupported) {
			reasoningLevels = EnumSet.of(ModelReasoningLevel.NONE, ModelReasoningLevel.LOW, ModelReasoningLevel.HIGH,
					ModelReasoningLevel.MAX);
		}
		else if (dialect == ModelEndpointDialect.MOONSHOT_NATIVE
				&& protocol == ModelReasoningProtocol.REASONING_EFFORT) {
			reasoningLevels = EnumSet.of(ModelReasoningLevel.NONE, ModelReasoningLevel.LOW,
					ModelReasoningLevel.HIGH, ModelReasoningLevel.MAX);
		}
		else if (protocol == ModelReasoningProtocol.REASONING_EFFORT || reasoningEffortSupported) {
			reasoningLevels = EnumSet.of(ModelReasoningLevel.NONE, ModelReasoningLevel.LOW,
					ModelReasoningLevel.MEDIUM, ModelReasoningLevel.HIGH);
		}
		else {
			reasoningLevels = EnumSet.of(ModelReasoningLevel.NONE);
		}
		return ModelRequestCapabilities.builder()
			.reasoningProtocols(EnumSet.of(protocol))
			.reasoningModes(EnumSet.allOf(ModelReasoningMode.class))
			.reasoningLevels(reasoningLevels)
			.reasoningBudgetSupported(reasoningBudgetSupported)
			.reasoningEffortSupported(reasoningEffortSupported)
			.reasoningDisableSupported(reasoningDisableSupported)
			.tokenAccounting(tokenAccounting)
			.tokenLimitModes(EnumSet.of(ModelTokenLimitMode.MAX_TOKENS))
			.temperatureSupported(temperatureSupported)
			.structuredOutputModes(EnumSet.of(ModelStructuredOutputMode.STRICT_JSON_SCHEMA,
					ModelStructuredOutputMode.JSON_OBJECT, ModelStructuredOutputMode.PROMPT_JSON))
			.preservedReasoningPolicies(EnumSet.of(ModelPreservedReasoningPolicy.DROP))
			.build();
	}

	private ModelRequestCapabilities unconstrainedCapabilities() {
		return ModelRequestCapabilities.builder()
			.reasoningProtocols(EnumSet.allOf(ModelReasoningProtocol.class))
			.reasoningModes(EnumSet.allOf(ModelReasoningMode.class))
			.reasoningLevels(EnumSet.allOf(ModelReasoningLevel.class))
			.reasoningBudgetSupported(true)
			.reasoningEffortSupported(true)
			.reasoningDisableSupported(true)
			.tokenAccounting(ModelTokenAccounting.UNKNOWN)
			.tokenLimitModes(EnumSet.allOf(ModelTokenLimitMode.class))
			.temperatureSupported(true)
			.structuredOutputModes(EnumSet.allOf(ModelStructuredOutputMode.class))
			.preservedReasoningPolicies(EnumSet.allOf(ModelPreservedReasoningPolicy.class))
			.build();
	}

	private ModelRequestCapabilities clampCapabilities(ModelRequestCapabilities defaults,
			ModelRequestCapabilities probe) {
		if (probe == null) {
			return defaults;
		}
		return ModelRequestCapabilities.builder()
			.reasoningProtocols(intersection(defaults.reasoningProtocols(), probe.reasoningProtocols()))
			.reasoningModes(intersection(defaults.reasoningModes(), probe.reasoningModes()))
			.reasoningLevels(intersection(defaults.reasoningLevels(), probe.reasoningLevels()))
			.reasoningBudgetSupported(Boolean.TRUE.equals(defaults.reasoningBudgetSupported())
					&& (probe.reasoningBudgetSupported() == null || Boolean.TRUE.equals(probe.reasoningBudgetSupported())))
			.reasoningEffortSupported(Boolean.TRUE.equals(defaults.reasoningEffortSupported())
					&& (probe.reasoningEffortSupported() == null || Boolean.TRUE.equals(probe.reasoningEffortSupported())))
			.reasoningDisableSupported(Boolean.TRUE.equals(defaults.reasoningDisableSupported())
					&& (probe.reasoningDisableSupported() == null || Boolean.TRUE.equals(probe.reasoningDisableSupported())))
			.tokenAccounting(probe.tokenAccounting() == null ? defaults.tokenAccounting() : probe.tokenAccounting())
			.tokenLimitModes(intersection(defaults.tokenLimitModes(), probe.tokenLimitModes()))
			.temperatureSupported(Boolean.TRUE.equals(defaults.temperatureSupported())
					&& (probe.temperatureSupported() == null || Boolean.TRUE.equals(probe.temperatureSupported())))
			.structuredOutputModes(intersection(defaults.structuredOutputModes(), probe.structuredOutputModes()))
			.preservedReasoningPolicies(intersection(defaults.preservedReasoningPolicies(),
					probe.preservedReasoningPolicies()))
			.build();
	}

	private <E extends Enum<E>> Set<E> intersection(Set<E> defaults, Set<E> probe) {
		if (probe == null) {
			return defaults;
		}
		if (defaults.isEmpty()) {
			return Set.of();
		}
		EnumSet<E> result = EnumSet.copyOf(defaults);
		result.retainAll(probe);
		return result;
	}

	private ModelReasoningProtocol resolveProtocol(ModelConfigDTO config, ModelRequestOptionsOverride task,
			ModelDialectDefaults defaults) {
		ModelReasoningProtocol requested = task.reasoningProtocol() != null ? task.reasoningProtocol()
				: parse(config.getReasoningProtocol(), ModelReasoningProtocol.class, ModelReasoningProtocol.AUTO);
		return requested == ModelReasoningProtocol.AUTO ? defaults.reasoningProtocol() : requested;
	}

	private ModelReasoningMode resolveMode(ModelConfigDTO config, ModelRequestOptionsOverride task,
			ModelDialectDefaults defaults) {
		ModelReasoningMode requested = task.reasoningMode() != null ? task.reasoningMode()
				: parse(config.getReasoningMode(), ModelReasoningMode.class, ModelReasoningMode.AUTO);
		return requested == ModelReasoningMode.AUTO ? defaults.reasoningMode() : requested;
	}

	private ModelReasoningLevel resolveLevel(ModelConfigDTO config, ModelRequestOptionsOverride task,
			ModelReasoningLevel defaultLevel) {
		if (task.reasoningLevel() != null) {
			return task.reasoningLevel();
		}
		if (config.getReasoningLevel() == null || config.getReasoningLevel().isBlank()) {
			return defaultLevel;
		}
		return parse(config.getReasoningLevel(), ModelReasoningLevel.class, defaultLevel);
	}

	private Long resolveBudget(ModelConfigDTO config, ModelRequestOptionsOverride task) {
		Long budget = task.reasoningBudgetTokens() != null ? task.reasoningBudgetTokens() : config.getReasoningBudgetTokens();
		if (budget != null && budget <= 0) {
			throw new IllegalArgumentException("reasoningBudgetTokens must be greater than zero");
		}
		return budget;
	}

	private ModelTokenLimitMode resolveTokenLimit(ModelConfigDTO config, ModelRequestOptionsOverride task,
			ModelDialectDefaults defaults) {
		ModelTokenLimitMode requested = task.tokenLimitMode() != null ? task.tokenLimitMode()
				: parse(config.getTokenLimitMode(), ModelTokenLimitMode.class, null);
		return requested == null ? defaults.tokenLimitMode() : requested;
	}

	private ModelTemperaturePolicy resolveTemperaturePolicy(ModelConfigDTO config, ModelRequestOptionsOverride task,
			ModelDialectDefaults defaults) {
		ModelTemperaturePolicy requested = task.temperaturePolicy() != null ? task.temperaturePolicy()
				: parse(config.getTemperaturePolicy(), ModelTemperaturePolicy.class, null);
		return requested == null ? defaults.temperaturePolicy() : requested;
	}

	private ModelStructuredOutputMode resolveStructuredOutput(ModelConfigDTO config, ModelRequestOptionsOverride task,
			ModelDialectDefaults defaults) {
		ModelStructuredOutputMode requested = task.structuredOutputMode() != null ? task.structuredOutputMode()
				: parse(config.getStructuredOutputMode(), ModelStructuredOutputMode.class, ModelStructuredOutputMode.AUTO);
		return requested == ModelStructuredOutputMode.AUTO ? defaults.structuredOutputMode() : requested;
	}

	private ModelPreservedReasoningPolicy resolvePreservedReasoning(ModelConfigDTO config,
			ModelRequestOptionsOverride task, ModelDialectDefaults defaults) {
		ModelPreservedReasoningPolicy requested = task.preservedReasoningPolicy() != null
				? task.preservedReasoningPolicy()
				: parse(config.getPreservedReasoningPolicy(), ModelPreservedReasoningPolicy.class, null);
		return requested == null ? defaults.preservedReasoningPolicy() : requested;
	}

	private ModelReasoningProtocol requireSupportedProtocol(ModelEndpointDialect dialect,
			ModelReasoningProtocol requested, Set<ModelReasoningProtocol> allowed) {
		if (allowed.contains(requested)) {
			return requested;
		}
		throw new IllegalArgumentException("reasoningProtocol " + requested + " is not supported by " + dialect);
	}

	private boolean requiresReasoningEffort(ModelReasoningProtocol protocol) {
		return protocol == ModelReasoningProtocol.REASONING_EFFORT
				|| protocol == ModelReasoningProtocol.THINKING_OBJECT_WITH_EFFORT;
	}

	private ModelReasoningMode clampMode(ModelReasoningMode requested, ModelReasoningMode dialectDefault,
			Set<ModelReasoningMode> allowed) {
		if (allowed.contains(requested)) {
			return requested;
		}
		if (allowed.contains(dialectDefault)) {
			return dialectDefault;
		}
		return allowed.contains(ModelReasoningMode.AUTO) ? ModelReasoningMode.AUTO : first(allowed);
	}

	private ModelReasoningLevel clampLevel(ModelEndpointDialect dialect, ModelReasoningLevel requested,
			ModelReasoningMode mode,
			Set<ModelReasoningLevel> allowed) {
		if (mode == ModelReasoningMode.DISABLED) {
			return allowed.contains(ModelReasoningLevel.NONE) ? ModelReasoningLevel.NONE : null;
		}
		if (requested == null || requested == ModelReasoningLevel.NONE) {
			return null;
		}
		if (allowed.contains(requested)) {
			return requested;
		}
		throw new IllegalArgumentException("reasoningLevel " + requested + " is not supported by " + dialect);
	}

	private ModelStructuredOutputMode clampStructuredOutput(ModelStructuredOutputMode requested,
			Set<ModelStructuredOutputMode> allowed) {
		if (requested == ModelStructuredOutputMode.STRICT_JSON_SCHEMA
				&& allowed.contains(ModelStructuredOutputMode.STRICT_JSON_SCHEMA)) {
			return requested;
		}
		if (requested != ModelStructuredOutputMode.PROMPT_JSON && allowed.contains(ModelStructuredOutputMode.JSON_OBJECT)) {
			return ModelStructuredOutputMode.JSON_OBJECT;
		}
		return ModelStructuredOutputMode.PROMPT_JSON;
	}

	private <E> E first(Set<E> values) {
		return values.stream().findFirst().orElse(null);
	}

	private <T> void requireUnchanged(String option, T requested, T defaultMarker, T resolved) {
		if (requested == null || Objects.equals(requested, defaultMarker) || Objects.equals(requested, resolved)) {
			return;
		}
		throw new IllegalArgumentException(option + " 的持久化值 " + requested + " 与实际生效值 " + resolved + " 不一致");
	}

	private Integer toInteger(Long value) {
		if (value == null) {
			return null;
		}
		if (value <= 0 || value > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("maxOutputTokens must be between 1 and " + Integer.MAX_VALUE);
		}
		return value.intValue();
	}

	private <E extends Enum<E>> E parse(String value, Class<E> type, E defaultValue) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unsupported model option " + value + " for " + type.getSimpleName(), ex);
		}
	}

}
