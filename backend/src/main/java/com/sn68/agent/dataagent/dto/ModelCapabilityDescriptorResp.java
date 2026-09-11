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
package com.sn68.agent.dataagent.dto;

import com.sn68.agent.dataagent.enums.ModelCapabilityProfile;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelPreservedReasoningPolicy;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelTemperaturePolicy;
import com.sn68.agent.dataagent.enums.ModelTokenLimitMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 服务端能力解析器生成的模型可选项描述，属于管理端契约：
 * 只暴露保存边界能接受的取值，Provider 传输细节由服务端持有。
 */
@Schema(description = "模型能力描述")
public record ModelCapabilityDescriptorResp(
		@Schema(description = "端点方言") ModelEndpointDialect endpointDialect,
		@Schema(description = "能力档位") ModelCapabilityProfile capabilityProfile,
		@Schema(description = "支持的推理协议列表") List<ModelReasoningProtocol> reasoningProtocols,
		@Schema(description = "支持的推理模式列表") List<ModelReasoningMode> reasoningModes,
		@Schema(description = "支持的推理级别列表") List<ModelReasoningLevel> reasoningLevels,
		@Schema(description = "是否支持推理预算") boolean reasoningBudgetSupported,
		@Schema(description = "是否支持推理强度") boolean reasoningEffortSupported,
		@Schema(description = "是否支持关闭推理") boolean reasoningDisableSupported,
		@Schema(description = "支持的Token上限模式列表") List<ModelTokenLimitMode> tokenLimitModes,
		@Schema(description = "支持的温度策略列表") List<ModelTemperaturePolicy> temperaturePolicies,
		@Schema(description = "支持的结构化输出模式列表") List<ModelStructuredOutputMode> structuredOutputModes,
		@Schema(description = "支持的保留推理策略列表") List<ModelPreservedReasoningPolicy> preservedReasoningPolicies,
		@Schema(description = "各能力默认值") Defaults defaults) {

	public ModelCapabilityDescriptorResp {
		reasoningProtocols = List.copyOf(reasoningProtocols);
		reasoningModes = List.copyOf(reasoningModes);
		reasoningLevels = List.copyOf(reasoningLevels);
		tokenLimitModes = List.copyOf(tokenLimitModes);
		temperaturePolicies = List.copyOf(temperaturePolicies);
		structuredOutputModes = List.copyOf(structuredOutputModes);
		preservedReasoningPolicies = List.copyOf(preservedReasoningPolicies);
	}

	/**
	 * 各能力项的服务端默认取值。
	 */
	@Schema(description = "模型能力默认值")
	public record Defaults(
			@Schema(description = "默认推理协议") ModelReasoningProtocol reasoningProtocol,
			@Schema(description = "默认推理模式") ModelReasoningMode reasoningMode,
			@Schema(description = "默认推理级别") ModelReasoningLevel reasoningLevel,
			@Schema(description = "默认Token上限模式") ModelTokenLimitMode tokenLimitMode,
			@Schema(description = "默认温度策略") ModelTemperaturePolicy temperaturePolicy,
			@Schema(description = "默认结构化输出模式") ModelStructuredOutputMode structuredOutputMode,
			@Schema(description = "默认保留推理策略") ModelPreservedReasoningPolicy preservedReasoningPolicy) {
	}

}
