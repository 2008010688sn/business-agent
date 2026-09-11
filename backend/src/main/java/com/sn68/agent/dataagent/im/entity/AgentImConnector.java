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
 * IM 对话连接器。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_im_connector")
@Schema(description = "AgentIM连接器实体")
public class AgentImConnector extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "连接器编码")
	private String connectorCode;

	@Schema(description = "连接器名称")
	private String connectorName;

	@Schema(description = "平台类型")
	private String provider;

	@Schema(description = "类型")
	private String authType;

	@Schema(description = "配置")
	private String encryptedConfig;

	@Schema(description = "credentialRef字段")
	private String credentialRef;

	@Schema(description = "Agent默认标识字段")
	private Long defaultAgentId;

	@Schema(description = "启用状态")
	private Boolean directEnabled;

	@Schema(description = "启用状态")
	private Boolean groupEnabled;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "displayOrder字段")
	private Integer displayOrder;

}
