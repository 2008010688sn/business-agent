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

import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts provider-independent reasoning settings to one configured wire dialect.
 */
public final class ModelEndpointDialectAdapter {

	private final ModelEndpointDialect dialect;

	private final ModelDialectDefaults defaults;

	private final ModelRequestCapabilities capabilities;

	public ModelEndpointDialectAdapter(ModelEndpointDialect dialect, ModelDialectDefaults defaults,
			ModelRequestCapabilities capabilities) {
		this.dialect = dialect;
		this.defaults = defaults;
		this.capabilities = capabilities;
	}

	public ModelEndpointDialect dialect() {
		return dialect;
	}

	public ModelDialectDefaults defaults() {
		return defaults;
	}

	public ModelRequestCapabilities capabilities() {
		return capabilities;
	}

	ResolvedModelRequestOptions adapt(ModelLogicalRequestOptions options) {
		Map<String, Object> extraBody = new LinkedHashMap<>();
		String reasoningEffort = null;
		switch (options.reasoningProtocol()) {
			case REASONING_EFFORT -> reasoningEffort = reasoningEffort(options);
			case ENABLE_THINKING, ENABLE_THINKING_ONLY -> applyEnableThinking(extraBody, options);
			case THINKING_OBJECT -> applyThinkingObject(extraBody, options);
			case THINKING_OBJECT_WITH_EFFORT -> {
				applyThinkingObject(extraBody, options);
				if (options.reasoningMode() != ModelReasoningMode.DISABLED
						&& options.reasoningLevel() != null
						&& options.reasoningLevel() != ModelReasoningLevel.NONE) {
					reasoningEffort = reasoningEffort(options);
				}
			}
			case NONE -> {
				// No provider reasoning field is emitted.
			}
			case AUTO -> throw new IllegalStateException("AUTO reasoning protocol must be resolved before adaptation");
		}
		return new ResolvedModelRequestOptions(dialect, options.reasoningProtocol(), options.reasoningMode(),
				options.reasoningLevel(), options.reasoningBudgetTokens(), options.maxOutputTokens(),
				options.tokenLimitMode(), options.tokenAccounting(), options.temperature(), options.temperaturePolicy(),
				options.structuredOutputMode(), options.preservedReasoningPolicy(), extraBody, reasoningEffort);
	}

	private String reasoningEffort(ModelLogicalRequestOptions options) {
		if (options.reasoningMode() == ModelReasoningMode.DISABLED) {
			return null;
		}
		ModelReasoningLevel level = options.reasoningLevel();
		return level == null || level == ModelReasoningLevel.NONE ? null : level.wireValue();
	}

	private void applyEnableThinking(Map<String, Object> extraBody, ModelLogicalRequestOptions options) {
		Boolean enabled = enabled(options);
		if (enabled != null) {
			extraBody.put("enable_thinking", enabled);
		}
		if (Boolean.TRUE.equals(enabled) && options.reasoningBudgetTokens() != null) {
			extraBody.put("thinking_budget", options.reasoningBudgetTokens());
		}
	}

	private void applyThinkingObject(Map<String, Object> extraBody, ModelLogicalRequestOptions options) {
		Boolean enabled = enabled(options);
		if (enabled == null) {
			return;
		}
		Map<String, Object> thinking = new LinkedHashMap<>();
		thinking.put("type", enabled ? "enabled" : "disabled");
		if (enabled && options.reasoningBudgetTokens() != null) {
			thinking.put("budget_tokens", options.reasoningBudgetTokens());
		}
		extraBody.put("thinking", Map.copyOf(thinking));
	}

	private Boolean enabled(ModelLogicalRequestOptions options) {
		if (options.reasoningMode() == ModelReasoningMode.ENABLED) {
			return true;
		}
		if (options.reasoningMode() == ModelReasoningMode.DISABLED) {
			return false;
		}
		if (options.reasoningBudgetTokens() != null) {
			return true;
		}
		ModelReasoningLevel level = options.reasoningLevel();
		return level == null ? null : level != ModelReasoningLevel.NONE;
	}

}
