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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.annotation.InEnum;
import com.sn68.agent.dataagent.enums.ModelCapabilityProfile;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelPreservedReasoningPolicy;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelTemperaturePolicy;
import com.sn68.agent.dataagent.enums.ModelTokenLimitMode;
import com.sn68.agent.dataagent.enums.ModelType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型配置数据传输对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "模型配置数据传输对象")
public class ModelConfigDTO {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "模型提供商")
	@NotBlank(message = "provider must not be empty")
	private String provider; // e.g. "openai", "deepseek"

	@Schema(description = "API Key（敏感信息）")
	private String apiKey;

	@Schema(description = "API Key是否已配置")
	private Boolean apiKeyConfigured;

	@Schema(description = "API基础地址")
	@NotBlank(message = "baseUrl must not be empty")
	private String baseUrl;

	@Schema(description = "模型名称")
	@NotBlank(message = "modelName must not be empty")
	private String modelName;

	@Schema(description = "端点方言")
	@Builder.Default
	@InEnum(value = ModelEndpointDialect.class, message = "endpointDialect is not supported")
	private String endpointDialect = ModelEndpointDialect.OPENAI_COMPATIBLE.name();

	@Schema(description = "模型能力档位")
	@Builder.Default
	@InEnum(value = ModelCapabilityProfile.class, message = "capabilityProfile is not supported")
	private String capabilityProfile = ModelCapabilityProfile.AUTO.name();

	@Schema(description = "推理协议")
	@Builder.Default
	@InEnum(value = ModelReasoningProtocol.class, message = "reasoningProtocol is not supported")
	private String reasoningProtocol = ModelReasoningProtocol.AUTO.name();

	@Schema(description = "推理模式")
	@Builder.Default
	@InEnum(value = ModelReasoningMode.class, message = "reasoningMode is not supported")
	private String reasoningMode = ModelReasoningMode.AUTO.name();

	@Schema(description = "推理级别")
	@InEnum(value = ModelReasoningLevel.class, message = "reasoningLevel is not supported")
	private String reasoningLevel;

	@Schema(description = "推理预算Token数")
	private Long reasoningBudgetTokens;

	@Schema(description = "Token上限模式")
	@InEnum(value = ModelTokenLimitMode.class, message = "tokenLimitMode is not supported")
	private String tokenLimitMode;

	@Schema(description = "温度策略")
	@InEnum(value = ModelTemperaturePolicy.class, message = "temperaturePolicy is not supported")
	private String temperaturePolicy;

	@Schema(description = "结构化输出模式")
	@Builder.Default
	@InEnum(value = ModelStructuredOutputMode.class, message = "structuredOutputMode is not supported")
	private String structuredOutputMode = ModelStructuredOutputMode.AUTO.name();

	@Schema(description = "保留推理策略")
	@Builder.Default
	@InEnum(value = ModelPreservedReasoningPolicy.class, message = "preservedReasoningPolicy is not supported")
	private String preservedReasoningPolicy = ModelPreservedReasoningPolicy.DROP.name();

	@Schema(description = "模型类型")
	@NotBlank(message = "modelType must not be empty")
	@InEnum(value = ModelType.class, message = "CHAT/EMBEDDING/AUDIO_TRANSCRIPTION/TEXT_TO_SPEECH/REALTIME_VOICE 之一")
	private String modelType;

	// 仅当厂商路径非标准时填写，例如 "/custom/chat"
	@Schema(description = "对话补全接口路径（非标准时填写）")
	private String completionsPath;

	// 仅当厂商路径非标准时填写
	@Schema(description = "向量接口路径（非标准时填写）")
	private String embeddingsPath;

	// 仅当语音转写接口路径非标准时填写
	@Schema(description = "语音转写接口路径（非标准时填写）")
	private String transcriptionsPath;

	// 对话模型是否支持图片输入
	@Schema(description = "是否支持图片输入")
	@Builder.Default
	private Boolean supportVision = false;

	@Schema(description = "默认温度")
	@Builder.Default
	private Double temperature = 0.0;

	@Schema(description = "最大输出Token数")
	@Builder.Default
	private Long maxTokens = 2000L;

	@Schema(description = "上下文窗口Token数")
	@Builder.Default
	private Long contextWindowTokens = 32768L;

	@Schema(description = "是否启用")
	@Builder.Default
	private Boolean isActive = true;

	// 模型代理配置，默认关闭（使用直连）
	@Schema(description = "是否启用代理")
	@Builder.Default
	private Boolean proxyEnabled = false;

	@Schema(description = "代理主机地址")
	private String proxyHost;

	@Schema(description = "代理端口")
	private Integer proxyPort;

	@Schema(description = "代理用户名")
	private String proxyUsername;

	@Schema(description = "代理密码（敏感信息）")
	private String proxyPassword;

	@Schema(description = "代理密码是否已配置")
	private Boolean proxyPasswordConfigured;

	/**
	 * 配置修订标记（最后修改时间）：不含敏感信息，可安全暴露，
	 * 用于路由能力快照检测模型配置是否过期。
	 */
	@Schema(description = "最后修改时间（配置修订标记）")
	private Instant lastModifyTime;

}
