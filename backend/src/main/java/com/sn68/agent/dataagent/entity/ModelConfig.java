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
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;

/**
 * 模型配置实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("model_config")
@Schema(description = "DataAgent模型配置")
public class ModelConfig extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "模型厂商")
	private String provider;

	@Schema(description = "基础URL")
	private String baseUrl;

	@Schema(description = "API Key")
	private String apiKey;

	@Schema(description = "模型名称")
	private String modelName;

	@Schema(description = "endpoint dialect")
	private String endpointDialect;

	@Schema(description = "explicit model-family capability profile")
	private String capabilityProfile;

	@Schema(description = "reasoning protocol")
	private String reasoningProtocol;

	@Schema(description = "reasoning mode")
	private String reasoningMode;

	@Schema(description = "reasoning level")
	private String reasoningLevel;

	@Schema(description = "reasoning budget tokens")
	private Long reasoningBudgetTokens;

	@Schema(description = "token limit field mode")
	private String tokenLimitMode;

	@Schema(description = "temperature field policy")
	private String temperaturePolicy;

	@Schema(description = "structured output mode")
	private String structuredOutputMode;

	@Schema(description = "preserved reasoning policy")
	private String preservedReasoningPolicy;

	@Schema(description = "温度参数")
	private Double temperature;

	@Schema(description = "是否启用")
	private Boolean isActive;

	@Schema(description = "最大Token数")
	private Long maxTokens;

	@Schema(description = "上下文窗口Token数")
	private Long contextWindowTokens;

	@Schema(description = "模型类型")
	private ModelType modelType;

	@Schema(description = "对话接口路径")
	@TableField(updateStrategy = FieldStrategy.ALWAYS)
	private String completionsPath;

	@Schema(description = "向量接口路径")
	private String embeddingsPath;

	@Schema(description = "语音转写接口路径")
	private String transcriptionsPath;

	@Schema(description = "是否支持图片输入")
	private Boolean supportVision;

	@Schema(description = "是否启用代理")
	private Boolean proxyEnabled;

	@Schema(description = "代理主机")
	private String proxyHost;

	@Schema(description = "代理端口")
	private Integer proxyPort;

	@Schema(description = "代理用户名")
	private String proxyUsername;

	@Schema(description = "代理密码")
	private String proxyPassword;

}
