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
package com.sn68.agent.dataagent.dto.agent;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent模型配置Item数据传输对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent模型配置Item数据传输对象")
public class AgentModelConfigItemResp {

	@Schema(description = "主键ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	@Schema(description = "Agent ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long modelConfigId;

	@Schema(description = "是否默认")
	private Boolean isDefault;

	@Schema(description = "数据表字段")
	private Boolean userSelectable;

	@Schema(description = "是否启用")
	private Boolean enabled;

	@Schema(description = "模型配置模式字段")
	private ModelConfigDTO modelConfig;

}
