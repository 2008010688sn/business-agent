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
import com.sn68.agent.dataagent.enums.ModelPreservedReasoningPolicy;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelTemperaturePolicy;
import com.sn68.agent.dataagent.enums.ModelTokenAccounting;
import com.sn68.agent.dataagent.enums.ModelTokenLimitMode;
import java.util.Map;

/**
 * Final request options after defaults, overrides, and capability clamping.
 */
public record ResolvedModelRequestOptions(ModelEndpointDialect endpointDialect,
		ModelReasoningProtocol reasoningProtocol, ModelReasoningMode reasoningMode,
		ModelReasoningLevel reasoningLevel, Long reasoningBudgetTokens, Integer maxOutputTokens,
		ModelTokenLimitMode tokenLimitMode, ModelTokenAccounting tokenAccounting, Double temperature,
		ModelTemperaturePolicy temperaturePolicy,
		ModelStructuredOutputMode structuredOutputMode,
		ModelPreservedReasoningPolicy preservedReasoningPolicy, Map<String, Object> extraBody,
		String reasoningEffort) {

	public ResolvedModelRequestOptions {
		extraBody = extraBody == null || extraBody.isEmpty() ? Map.of() : Map.copyOf(extraBody);
	}

}
