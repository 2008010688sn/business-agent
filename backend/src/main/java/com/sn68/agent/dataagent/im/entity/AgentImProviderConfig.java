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
package com.sn68.agent.dataagent.im.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * IM 平台级配置。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_im_provider_config")
@Schema(description = "AgentIM平台配置实体")
public class AgentImProviderConfig extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * 平台级共享配置允许为空（NULL 表示全局配置）；租户级覆盖配置填租户ID。
	 * 运行时读取仍按 provider 全局解析，见 {@code ImRuntimeConfigService}。
	 */
	@Schema(description = "所属租户ID（NULL 表示平台级全局配置）")
	private String tenantId;

	@Schema(description = "平台类型")
	private String provider;

	@Schema(description = "配置")
	private String encryptedConfig;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "displayOrder字段")
	private Integer displayOrder;

}
