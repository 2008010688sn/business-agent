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

import com.sn68.agent.dataagent.enums.ModelPreservedReasoningPolicy;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelTokenAccounting;
import com.sn68.agent.dataagent.enums.ModelTokenLimitMode;
import java.util.Set;
import lombok.Builder;

/**
 * Optional capability snapshot produced by endpoint probing.
 *
 * <p>A null member means that the dialect capability remains authoritative. An
 * empty set explicitly means that the endpoint supports none of those options.</p>
 */
@Builder
public record ModelRequestCapabilities(Set<ModelReasoningProtocol> reasoningProtocols,
	Set<ModelReasoningMode> reasoningModes, Set<ModelReasoningLevel> reasoningLevels,
		Boolean reasoningBudgetSupported, Boolean reasoningEffortSupported, Boolean reasoningDisableSupported,
		ModelTokenAccounting tokenAccounting,
		Set<ModelTokenLimitMode> tokenLimitModes,
		Boolean temperatureSupported, Set<ModelStructuredOutputMode> structuredOutputModes,
		Set<ModelPreservedReasoningPolicy> preservedReasoningPolicies) {

	public ModelRequestCapabilities {
		reasoningProtocols = immutable(reasoningProtocols);
		reasoningModes = immutable(reasoningModes);
		reasoningLevels = immutable(reasoningLevels);
		tokenLimitModes = immutable(tokenLimitModes);
		structuredOutputModes = immutable(structuredOutputModes);
		preservedReasoningPolicies = immutable(preservedReasoningPolicies);
	}

	private static <T> Set<T> immutable(Set<T> values) {
		return values == null ? null : Set.copyOf(values);
	}

}
